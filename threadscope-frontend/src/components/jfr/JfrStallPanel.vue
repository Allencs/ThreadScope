<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import {
  fetchJfrStalls,
  type JfrBucketRange,
  type JfrStallEvent,
  type JfrStallGroup,
  type JfrStallGroupBy,
  type JfrStallReport,
  type JfrStallTrace,
} from '@/api/threadscope'
import HintTip from '@/components/common/HintTip.vue'
import { formatBytes, formatNanos } from '@/utils/bytes'
import { methodParts } from '@/utils/methodKey'

const props = defineProps<{ reportId: string; range: JfrBucketRange | null; rangeText: string; pkgs: string[] }>()
const thread = defineModel<string>('thread', { default: '' })
const emit = defineEmits<{ clearRange: [] }>()

const FRAME_PREVIEW = 14
const EVENT_PREVIEW = 8

const group = ref<JfrStallGroupBy>('business')
const report = ref<JfrStallReport | null>(null)
const loading = ref(false)
const errorMsg = ref('')
const opened = ref<Record<string, boolean>>({})
const fullTrace = ref<Record<string, boolean>>({})
const allEvents = ref<Record<string, boolean>>({})

let seq = 0
let threadTimer = 0

async function load() {
  const current = ++seq
  loading.value = true
  errorMsg.value = ''
  try {
    const data = await fetchJfrStalls(props.reportId, {
      group: group.value,
      thread: thread.value,
      range: props.range,
      pkgs: props.pkgs,
    })
    if (current !== seq) return
    report.value = data
    opened.value = data.groups.length ? { [data.groups[0].key]: true } : {}
    fullTrace.value = {}
    allEvents.value = {}
  } catch (e) {
    if (current !== seq) return
    errorMsg.value = e instanceof Error ? e.message : '分配停顿加载失败'
  } finally {
    if (current === seq) loading.value = false
  }
}

watch(() => [props.reportId, props.range?.from, props.range?.to, props.pkgs.join(','), group.value], load, { immediate: true })
watch(thread, () => {
  window.clearTimeout(threadTimer)
  threadTimer = window.setTimeout(load, 250)
})
onBeforeUnmount(() => window.clearTimeout(threadTimer))

/** 按调用栈分组时 key 是栈编号，方法取栈顶帧 */
function headKey(g: JfrStallGroup): string | null {
  if (g.key === '-') return null
  return report.value?.group === 'business' ? g.key : (g.traces[0]?.frames[0]?.methodKey ?? null)
}

/** 按调用栈分组时，栈顶通常是 JDK / 框架方法，旁边提示它属于哪段业务代码 */
function anchorOf(g: JfrStallGroup): string | null {
  if (report.value?.group !== 'stack') return null
  const t = g.traces[0]
  if (!t || t.anchor == null || t.anchor === 0) return null
  return methodParts(t.frames[t.anchor].methodKey).name
}

function visibleFrames(t: JfrStallTrace, key: string) {
  if (fullTrace.value[key]) return t.frames
  return t.frames.slice(0, Math.max(FRAME_PREVIEW, (t.anchor ?? 0) + 4))
}

function visibleEvents(g: JfrStallGroup): JfrStallEvent[] {
  return allEvents.value[g.key] ? g.events : g.events.slice(0, EVENT_PREVIEW)
}

function typesText(g: JfrStallGroup): string {
  return Object.entries(g.types).map(([name, n]) => `${name} ${n} 次`).join(' · ')
}

function clock(epochMs: number): string {
  const d = new Date(epochMs)
  return `${d.toLocaleTimeString([], { hour12: false })}.${String(d.getMilliseconds()).padStart(3, '0')}`
}

function tone(percent: number): string {
  if (percent >= 30) return 'hot'
  if (percent >= 10) return 'warm'
  return 'cool'
}
</script>

<template>
  <section class="card stall">
    <div class="card-title">
      <h2>分配停顿明细</h2>
      <span class="muted">哪段代码在等 ZGC 腾出内存</span>
      <HintTip heading="怎么看" :width="460">
        <p>每条 <code>jdk.ZAllocationStall</code> 都是一个线程申请内存时，ZGC 来不及回收，只能原地等待。停顿时间直接叠加在这次请求的 RT 上。</p>
        <p><b>按业务代码</b>（推荐）：从栈顶往下找第一个业务方法，同一个业务方法的停顿合成一组，看哪些接口被拖慢。<b>按调用栈</b>：每条完整栈一组，看具体卡在哪个 new 上。</p>
        <p class="hint-note">停顿的线程是「撞上」内存不足的线程，不一定是分配最多的。要减少停顿，还是要降低上方分配热点里排前面的代码的分配量，或者给堆留出更多余量。</p>
      </HintTip>
    </div>

    <div class="tools">
      <div class="seg">
        <button :class="{ on: group === 'business' }" @click="group = 'business'">按业务代码</button>
        <button :class="{ on: group === 'stack' }" @click="group = 'stack'">按调用栈</button>
      </div>
      <label class="search">
        <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M8.5 3a5.5 5.5 0 1 0 3.36 9.86l3.64 3.64 1.06-1.06-3.64-3.64A5.5 5.5 0 0 0 8.5 3Zm0 1.5a4 4 0 1 1 0 8 4 4 0 0 1 0-8Z" /></svg>
        <input v-model="thread" placeholder="线程名，例如 tomcat-exec" />
        <button v-if="thread" type="button" class="clear" @click="thread = ''">×</button>
      </label>
      <button v-if="range" type="button" class="filter-chip" title="取消区间（Esc）" @click="emit('clearRange')">
        仅统计 {{ rangeText }}<span class="x">×</span>
      </button>
      <span class="spacer"></span>
      <span v-if="loading" class="busy"><span class="spinner"></span>加载中</span>
      <span v-if="report" class="muted">
        {{ report.count }} 次 · 累计 {{ formatNanos(report.durationNanos) }} · 最长 {{ formatNanos(report.longestNanos) }} · {{ report.threadCount }} 个线程
      </span>
    </div>

    <p v-if="errorMsg" class="error">{{ errorMsg }}</p>
    <p v-if="report && !report.stacksAvailable" class="notice">
      这份报告解析时还没有保存停顿的调用栈，重新上传 .jfr 即可查看；下面只按线程和耗时统计。
    </p>

    <div v-if="report" class="groups" :class="{ dim: loading }">
      <div v-for="g in report.groups" :key="g.key" class="group">
        <div class="g-line" :class="{ open: opened[g.key] }" @click="opened[g.key] = !opened[g.key]">
          <span class="chevron">{{ opened[g.key] ? '▾' : '▸' }}</span>
          <template v-if="headKey(g)">
            <span class="pkg">{{ methodParts(headKey(g)!).pkg }}</span><span class="method-name">{{ methodParts(headKey(g)!).name }}</span><span class="params">{{ methodParts(headKey(g)!).params }}</span>
          </template>
          <span v-else class="method-name plain">{{ g.label }}</span>
          <span v-if="report.group === 'business' && !g.business && g.key !== '-'" class="tag gray" title="整条栈都没有业务代码，按栈顶方法归组">无业务代码</span>
          <span v-if="anchorOf(g)" class="via">← {{ anchorOf(g) }}</span>
          <span class="badge" :class="tone(g.percent)">{{ g.percent.toFixed(1) }}%</span>
          <span class="stat">{{ g.count }} 次 · 累计 {{ formatNanos(g.durationNanos) }} · 最长 {{ formatNanos(g.longestNanos) }}</span>
          <span class="sub">{{ g.threadCount }} 个线程<template v-if="report.group === 'business' && g.stackCount > 1"> · {{ g.stackCount }} 种栈</template></span>
        </div>

        <div v-if="opened[g.key]" class="detail">
          <div class="d-row">
            <span class="d-label">线程</span>
            <button
              v-for="t in g.threads"
              :key="t.name"
              type="button"
              class="chip"
              :class="{ on: thread === t.name }"
              title="只看这个线程"
              @click="thread = thread === t.name ? '' : t.name"
            >
              {{ t.name }}<em>{{ t.count }} 次 · {{ formatNanos(t.durationNanos) }}</em>
            </button>
            <span v-if="g.threadCount > g.threads.length" class="muted">等 {{ g.threadCount }} 个线程</span>
          </div>
          <div class="d-row">
            <span class="d-label">页类型</span>
            <span class="d-text">{{ typesText(g) }}</span>
            <span v-if="g.sizeBytes" class="muted">· 停顿时共申请 {{ formatBytes(g.sizeBytes) }}</span>
          </div>

          <div v-for="(t, i) in g.traces" :key="i" class="trace">
            <div class="t-head">
              <b>调用栈{{ g.traces.length > 1 ? ` #${i + 1}` : '' }}</b>
              <span class="muted">{{ t.count }} 次 · 累计 {{ formatNanos(t.durationNanos) }}</span>
              <span v-if="g.stackCount > g.traces.length && i === g.traces.length - 1" class="muted">· 只列出耗时最多的 {{ g.traces.length }} 条，共 {{ g.stackCount }} 种</span>
            </div>
            <ol class="frames">
              <li
                v-for="(f, k) in visibleFrames(t, `${g.key}|${i}`)"
                :key="k"
                :class="{ biz: f.business, anchor: k === t.anchor }"
                :title="f.methodKey"
              >
                <span class="at">at</span>
                <span class="f-pkg">{{ methodParts(f.methodKey).pkg }}</span><span class="f-name">{{ methodParts(f.methodKey).name }}</span><span class="f-params">{{ methodParts(f.methodKey).params }}</span>
                <span v-if="k === t.anchor" class="tag green">首个业务帧</span>
              </li>
            </ol>
            <div class="t-foot">
              <button
                v-if="visibleFrames(t, `${g.key}|${i}`).length < t.frames.length"
                type="button"
                class="link"
                @click="fullTrace[`${g.key}|${i}`] = true"
              >
                展开剩余 {{ t.frames.length - visibleFrames(t, `${g.key}|${i}`).length }} 帧
              </button>
              <span v-if="t.truncated" class="muted">栈深超过 JFR 的 stackdepth，底部已截断</span>
            </div>
          </div>

          <div class="events">
            <div class="t-head"><b>耗时最长的停顿</b><span class="muted">{{ g.events.length < g.count ? `前 ${g.events.length} 次` : `${g.count} 次` }}</span></div>
            <table>
              <thead>
                <tr><th>时间</th><th>线程</th><th class="num">停顿</th><th>页类型</th><th class="num">申请大小</th></tr>
              </thead>
              <tbody>
                <tr v-for="(e, k) in visibleEvents(g)" :key="k">
                  <td class="time">{{ clock(e.epochMs) }}</td>
                  <td class="thread" :title="e.thread ?? ''">{{ e.thread ?? '?' }}</td>
                  <td class="num strong">{{ formatNanos(e.durationNanos) }}</td>
                  <td>{{ e.type ?? '—' }}</td>
                  <td class="num">{{ e.size != null ? formatBytes(e.size) : '—' }}</td>
                </tr>
              </tbody>
            </table>
            <button v-if="g.events.length > EVENT_PREVIEW && !allEvents[g.key]" type="button" class="link" @click="allEvents[g.key] = true">
              显示全部 {{ g.events.length }} 条
            </button>
          </div>
        </div>
      </div>
      <p v-if="!loading && report.groups.length === 0" class="empty">没有符合条件的分配停顿。</p>
      <p v-if="report.groupCount > report.groups.length" class="more muted">只显示耗时最多的 {{ report.groups.length }} 组，共 {{ report.groupCount }} 组</p>
    </div>
  </section>
</template>

<style scoped>
.card { background: var(--ts-bg-surface); border: var(--ts-border); border-radius: 14px; box-shadow: var(--ts-shadow-xs); padding: 16px 18px; min-width: 0; }
.card-title { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.card-title h2 { margin: 0; font-size: 15px; font-weight: 650; }
.muted { color: var(--ts-text-secondary); font-size: 12.5px; white-space: nowrap; }
.spacer { flex: 1; }
.error { color: var(--ts-danger); margin: 0 0 8px; }
.notice { margin: 0 0 10px; padding: 7px 10px; border-radius: 8px; background: #fffbeb; color: #92400e; font-size: 12.5px; }
.hint-note { color: var(--ts-text-secondary); }

.tools { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 10px; }
.seg { display: inline-flex; background: var(--ts-bg-inset); border-radius: 9px; padding: 3px; }
.seg button { border: 0; background: transparent; padding: 5px 12px; border-radius: 7px; font: inherit; font-size: 12.5px; color: var(--ts-text-secondary); cursor: pointer; white-space: nowrap; }
.seg button.on { background: var(--ts-bg-surface); color: var(--ts-text-primary); font-weight: 600; box-shadow: var(--ts-shadow-sm); }
.search { position: relative; display: flex; align-items: center; }
.search svg { position: absolute; left: 10px; width: 15px; height: 15px; fill: var(--ts-text-muted); }
.search input { width: 240px; border: var(--ts-border); border-radius: 9px; padding: 7px 28px 7px 32px; font: inherit; font-size: 13px; background: var(--ts-bg-surface); color: var(--ts-text-primary); outline: none; }
.search input:focus { border-color: var(--ts-accent); box-shadow: var(--ts-shadow-ring); }
.clear { position: absolute; right: 6px; border: 0; background: transparent; color: var(--ts-text-muted); font-size: 16px; cursor: pointer; line-height: 1; padding: 2px 4px; }
.filter-chip {
  display: inline-flex; align-items: center; gap: 6px; padding: 3px 6px 3px 10px;
  border: 1px solid #bfdbfe; border-radius: 999px; background: #eff6ff; color: #1d4ed8;
  font: inherit; font-size: 12px; font-weight: 600; cursor: pointer; white-space: nowrap; font-variant-numeric: tabular-nums;
}
.filter-chip:hover { background: #dbeafe; }
.filter-chip .x { display: inline-grid; place-items: center; width: 16px; height: 16px; border-radius: 50%; background: #bfdbfe; font-size: 12px; line-height: 1; }
.busy { display: inline-flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--ts-text-secondary); }
.spinner { width: 13px; height: 13px; border-radius: 50%; border: 2px solid #dbe3f0; border-top-color: var(--ts-accent); animation: spin 0.8s linear infinite; flex: none; }
@keyframes spin { to { transform: rotate(360deg); } }
.link { border: 0; background: transparent; padding: 0; color: var(--ts-accent); font: inherit; font-size: 12px; cursor: pointer; }
.link:hover { text-decoration: underline; }

.groups { transition: opacity var(--ts-transition); container-type: inline-size; }
.groups.dim { opacity: 0.55; }
.group { padding: 1px 0; }
.g-line { display: flex; align-items: center; min-width: 0; white-space: nowrap; min-height: 30px; padding: 4px 6px; border-radius: 6px; cursor: pointer; transition: background var(--ts-transition); }
.g-line:hover { background: var(--ts-bg-hover); }
.g-line.open { background: #fef2f2; box-shadow: inset 3px 0 0 #dc2626; }
.g-line > .pkg, .g-line > .method-name, .g-line > .params, .g-line > .via, .g-line > .stat { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chevron { width: 16px; flex: none; color: var(--ts-text-muted); font-size: 12px; }
.pkg { flex: 0 1 auto; color: var(--ts-text-primary); font-family: var(--ts-font-display); font-size: 13px; }
.method-name { flex: none; max-width: 50%; color: var(--ts-accent); font-weight: 700; font-family: var(--ts-font-display); font-size: 13px; }
.method-name.plain { color: var(--ts-text-secondary); font-weight: 600; }
.params { flex: 0 1 auto; color: var(--ts-text-muted); font-family: var(--ts-font-display); font-size: 11.5px; max-width: 260px; }
.via { flex: 0 1 auto; margin-left: 8px; color: #15803d; font-family: var(--ts-font-display); font-size: 11.5px; }
.tag { flex: none; margin-left: 8px; font-size: 11px; font-weight: 650; padding: 0 7px; border-radius: 999px; line-height: 18px; }
.tag.gray { background: #f1f5f9; color: #64748b; }
.tag.green { background: #dcfce7; color: #15803d; }
.badge { flex: none; margin-left: 8px; padding: 0 6px; border-radius: 4px; font-size: 11px; line-height: 18px; font-weight: 700; color: #fff; font-variant-numeric: tabular-nums; }
.badge.hot { background: #dc2626; }
.badge.warm { background: #ea580c; }
.badge.cool { background: #d97706; }
.stat { flex: 0 1 auto; margin-left: 8px; padding: 0 6px; border-radius: 4px; background: #fef2f2; color: #b91c1c; font-size: 11px; font-weight: 600; line-height: 18px; font-variant-numeric: tabular-nums; }
.sub { flex: none; margin-left: auto; padding-left: 12px; color: var(--ts-text-muted); font-size: 11px; font-variant-numeric: tabular-nums; }
@container (max-width: 760px) { .g-line > .sub, .g-line > .via { display: none; } }

.detail { padding: 6px 0 12px 22px; display: flex; flex-direction: column; gap: 8px; }
.d-row { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; font-size: 12px; }
.d-label { width: 44px; flex: none; color: var(--ts-text-muted); font-size: 11.5px; }
.d-text { color: var(--ts-text-secondary); }
.chip { display: inline-flex; align-items: center; gap: 6px; max-width: 100%; border: var(--ts-border); background: var(--ts-bg-surface); border-radius: 6px; padding: 1px 8px; font: inherit; font-family: var(--ts-font-display); font-size: 11.5px; color: var(--ts-text-primary); cursor: pointer; white-space: nowrap; }
.chip em { font-style: normal; color: #b91c1c; font-family: var(--ts-font-sans, inherit); font-variant-numeric: tabular-nums; }
.chip:hover { border-color: #93c5fd; background: #eff6ff; }
.chip.on { border-color: #3b82f6; background: #eff6ff; }

.trace, .events { border: var(--ts-border); border-radius: 10px; padding: 8px 10px; background: #fcfcfd; }
.t-head { display: flex; align-items: center; gap: 8px; font-size: 12px; margin-bottom: 4px; }
.frames { list-style: none; margin: 0; padding: 0; font-family: var(--ts-font-display); font-size: 12px; line-height: 21px; }
.frames li { display: flex; align-items: center; min-width: 0; white-space: nowrap; padding: 0 6px; border-radius: 4px; color: var(--ts-text-muted); }
.frames li > span:not(.tag) { overflow: hidden; text-overflow: ellipsis; min-width: 0; }
.frames .at { flex: none; width: 18px; color: #cbd5e1; }
.frames .f-pkg { flex: 0 1 auto; }
.frames .f-name { flex: none; color: var(--ts-text-secondary); }
.frames .f-params { flex: 0 1 auto; font-size: 11px; }
.frames li.biz .f-pkg, .frames li.biz .f-name { color: #166534; }
.frames li.biz .f-name { font-weight: 650; }
.frames li.anchor { background: #f0fdf4; box-shadow: inset 2px 0 0 #16a34a; }
.t-foot { display: flex; gap: 12px; align-items: center; margin-top: 2px; }
.t-foot:empty { display: none; }

table { width: 100%; border-collapse: collapse; font-size: 12px; table-layout: fixed; }
th { text-align: left; font-weight: 600; color: var(--ts-text-muted); font-size: 11.5px; padding: 3px 6px; border-bottom: 1px solid #eef2f7; }
td { padding: 3px 6px; color: var(--ts-text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
th:nth-child(1) { width: 110px; }
th:nth-child(3) { width: 80px; }
th:nth-child(4) { width: 70px; }
th:nth-child(5) { width: 80px; }
.num { text-align: right; font-variant-numeric: tabular-nums; }
.time { font-variant-numeric: tabular-nums; }
th.num { text-align: right; }
td.thread { font-family: var(--ts-font-display); font-size: 11.5px; color: var(--ts-text-primary); }
td.strong { color: #b91c1c; font-weight: 650; }
tbody tr:hover td { background: var(--ts-bg-hover); }
.events .link { margin-top: 4px; }
.empty { text-align: center; color: var(--ts-text-muted); padding: 24px 0; margin: 0; }
.more { margin: 8px 0 0 6px; }
</style>
