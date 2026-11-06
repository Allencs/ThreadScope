<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, provide, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  fetchJfrCallers,
  fetchJfrCpu,
  fetchJfrHotspots,
  fetchJfrReport,
  openJfrDump,
  type JfrBucketRange,
  type JfrCpuTimeline,
  type JfrHotspotRow,
  type JfrReportSummary,
  type JfrTreeBasis,
} from '@/api/threadscope'
import HintTip from '@/components/common/HintTip.vue'
import CallerTree from '@/components/jfr/CallerTree.vue'
import JfrCpuTrend, { type CpuTrendMethod } from '@/components/jfr/JfrCpuTrend.vue'
import JfrThreadsPanel from '@/components/jfr/JfrThreadsPanel.vue'

const route = useRoute()
const router = useRouter()
const reportId = computed(() => route.params.reportId as string)

const report = ref<JfrReportSummary | null>(null)
const tab = ref<'hot' | 'threads' | 'dumps'>('hot')
const threadsVisited = ref(false)
const sort = ref<'self' | 'inclusive'>('self')
const query = ref('')
const hotspots = ref<JfrHotspotRow[]>([])
const totalSamples = ref(0)
const errorMsg = ref('')
const loading = ref(false)
const opened = ref<Record<string, boolean>>({})

let queryTimer = 0
let hotspotSeq = 0

const exactPaths = ref(true)
const truncatedSamples = ref<number | null>(null)
const cpu = ref<JfrCpuTimeline | null>(null)
const pinnedKey = ref<string | null>(null)
const previewKey = ref<string | null>(null)
const range = ref<JfrBucketRange | null>(null)
const rangeSupported = ref(false)

const rangeText = computed(() => {
  const r = range.value
  const t = cpu.value
  if (!r || !t) return ''
  const bucket = (t.endEpochMs - t.startEpochMs) / Math.max(1, t.bucketCount)
  const at = (i: number) => new Date(t.startEpochMs + i * bucket).toLocaleTimeString([], { hour12: false })
  return `${at(r.from)} – ${at(r.to)}`
})

/** 统计口径变了，调用者树里按需加载过的节点都要作废 */
const treeScope = ref('')

function selectRange(next: JfrBucketRange | null) {
  if (next?.from === range.value?.from && next?.to === range.value?.to) return
  range.value = next
  loadHotspots()
}

function onKey(event: KeyboardEvent) {
  if (event.key === 'Escape' && range.value && tab.value === 'hot') selectRange(null)
}

const truncatedRatio = computed(() =>
  truncatedSamples.value != null && totalSamples.value > 0 ? (truncatedSamples.value * 100) / totalSamples.value : 0)

const focusRow = computed(() => {
  const key = previewKey.value ?? pinnedKey.value
  return hotspots.value.find(r => r.methodKey === key) ?? hotspots.value[0] ?? null
})

const focusMethod = computed<CpuTrendMethod | null>(() => {
  const row = focusRow.value
  if (!row) return null
  const parts = methodParts(row.methodKey)
  return { pkg: parts.pkg, name: parts.name + parts.params, basis: sort.value, timeline: row.timeline }
})

const focusMode = computed(() => {
  const key = focusRow.value?.methodKey
  if (key != null && key === previewKey.value && key !== pinnedKey.value) return 'preview'
  return key != null && key === pinnedKey.value ? 'pinned' : 'default'
})

function coresLabel(row: JfrHotspotRow): string {
  const hw = cpu.value?.hwThreads
  if (row.machineCpu == null || !hw) return ''
  const cores = row.machineCpu * hw
  return cores < 0.01 ? '<0.01 核' : `${cores.toFixed(2)} 核`
}

function selectRow(row: JfrHotspotRow) {
  pinnedKey.value = row.methodKey
  toggle(row)
}

provide('jfrLoadCallers', (path: string[], basis: JfrTreeBasis) =>
  fetchJfrCallers(reportId.value, path, basis, query.value.trim(), range.value))
provide('jfrTotalSamples', totalSamples)

function formatDuration(ms: number): string {
  const seconds = Math.max(0, Math.round(ms / 1000))
  if (seconds < 90) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 90) return `${minutes} 分 ${seconds % 60} 秒`
  const hours = Math.floor(minutes / 60)
  return `${hours} 小时 ${minutes % 60} 分`
}

const jvmShort = computed(() => {
  const v = report.value?.jvmVersion
  if (!v) return ''
  const m = v.match(/\(([^)]+)\)/)
  return m ? `JDK ${m[1]}` : v.split(' for ')[0]
})

function percentTone(percent: number): string {
  if (percent >= 50) return 'hot'
  if (percent >= 20) return 'warm'
  if (percent >= 5) return 'mid'
  return 'cool'
}

function methodParts(methodKey: string): { pkg: string; name: string; params: string } {
  const hash = methodKey.indexOf('#')
  const className = hash < 0 ? methodKey : methodKey.slice(0, hash)
  const rest = hash < 0 ? '' : methodKey.slice(hash + 1)
  const paren = rest.indexOf('(')
  const method = paren < 0 ? rest : rest.slice(0, paren)
  const params = paren < 0 ? '' : rest.slice(paren)
  const dot = className.lastIndexOf('.')
  const pkg = dot < 0 ? '' : className.slice(0, dot + 1)
  const simple = dot < 0 ? className : className.slice(dot + 1)
  return { pkg, name: method ? `${simple}.${method}` : simple, params }
}

function primary(row: JfrHotspotRow): number {
  return sort.value === 'self' ? row.selfPercent : row.inclusivePercent
}

const maxPrimary = computed(() => Math.max(0.01, ...hotspots.value.map(primary)))

function secondary(row: JfrHotspotRow): number {
  return sort.value === 'self' ? row.inclusivePercent : row.selfPercent
}

async function loadHotspots() {
  const seq = ++hotspotSeq
  loading.value = true
  errorMsg.value = ''
  try {
    const data = await fetchJfrHotspots(reportId.value, query.value.trim(), sort.value, range.value)
    if (seq !== hotspotSeq) return
    hotspots.value = data.methods
    totalSamples.value = data.totalSamples
    exactPaths.value = data.exactPaths
    truncatedSamples.value = data.truncatedSamples
    rangeSupported.value = data.rangeSupported
    treeScope.value = `${data.rangeFrom ?? ''}-${data.rangeTo ?? ''}|${data.sort}|${query.value.trim()}`
    previewKey.value = null
    if (!data.methods.some(r => r.methodKey === pinnedKey.value)) pinnedKey.value = null
    for (const row of data.methods) {
      if (row.expand) opened.value[row.methodKey] = true
    }
  } catch (e) {
    if (seq !== hotspotSeq) return
    errorMsg.value = e instanceof Error ? e.message : '热点加载失败'
  } finally {
    if (seq === hotspotSeq) loading.value = false
  }
}

function setSort(value: 'self' | 'inclusive') {
  if (sort.value === value) return
  sort.value = value
  loadHotspots()
}

function scheduleHotspots() {
  window.clearTimeout(queryTimer)
  queryTimer = window.setTimeout(loadHotspots, 250)
}

function clearQuery() {
  query.value = ''
  loadHotspots()
}

function toggle(row: JfrHotspotRow) {
  if (row.callers.length === 0) return
  opened.value[row.methodKey] = !opened.value[row.methodKey]
}

function switchTab(value: 'hot' | 'threads' | 'dumps') {
  tab.value = value
  if (value === 'threads') threadsVisited.value = true
}

async function openDump() {
  try {
    const openedDump = await openJfrDump(reportId.value)
    router.push({ path: `/analysis/${openedDump.analysisId}/dashboard`, query: { jfr: reportId.value } })
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '无法打开线程 Dump'
  }
}

onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

onMounted(async () => {
  window.addEventListener('keydown', onKey)
  try {
    report.value = await fetchJfrReport(reportId.value)
    fetchJfrCpu(reportId.value).then(data => { cpu.value = data }).catch(() => { cpu.value = null })
    await loadHotspots()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '报告不存在'
  }
})
</script>

<template>
  <div class="report-page">
    <header class="report-head">
      <button class="back" type="button" @click="router.push('/jfr')">← 返回列表</button>
      <div class="title-row">
        <span class="file-badge">JFR</span>
        <div class="title-block">
          <h1 :title="report?.filename">{{ report?.filename || 'JFR 报告' }}</h1>
          <div v-if="report" class="meta">
            <span class="meta-item"><b>{{ formatDuration(report.durationMillis) }}</b>录制时长</span>
            <span class="meta-item"><b>{{ report.sampleCount.toLocaleString() }}</b>CPU 采样</span>
            <span class="meta-item"><b>{{ report.threadDumpCount }}</b>段线程 Dump</span>
            <span v-if="jvmShort" class="meta-item jvm" :title="report.jvmVersion ?? ''">{{ jvmShort }}</span>
          </div>
        </div>
      </div>
      <nav class="tabs">
        <button :class="{ on: tab === 'hot' }" @click="switchTab('hot')">热点方法</button>
        <button :class="{ on: tab === 'threads' }" @click="switchTab('threads')">线程</button>
        <button :class="{ on: tab === 'dumps' }" @click="switchTab('dumps')">
          线程 Dump<span v-if="report?.threadDumpCount" class="tab-count">{{ report.threadDumpCount }}</span>
        </button>
      </nav>
    </header>

    <p v-if="errorMsg" class="error">{{ errorMsg }}</p>

    <div class="report-scroll">
      <section v-if="cpu" v-show="tab === 'hot'" class="card cpu-card">
        <JfrCpuTrend
          :timeline="cpu"
          :method="focusMethod"
          :mode="focusMode"
          :range="range"
          :range-enabled="rangeSupported"
          @select="selectRange"
        />
      </section>
      <section v-show="tab === 'hot'" class="card hot">
        <div class="hot-tools">
          <div class="seg">
            <span class="seg-item">
              <button :class="{ on: sort === 'self' }" @click="setSort('self')">按 self</button>
              <div class="pop" role="tooltip">
                <h4><span class="pop-tag self">self</span>CPU 实际烧在哪</h4>
                <p>只统计方法本身在<b>栈顶执行</b>的样本，是真正占着 CPU 在跑的代码。</p>
                <p class="pop-label">排查 CPU 高，第一步按 self 排序，看排在最前的几个方法：</p>
                <ul>
                  <li>循环、正则、序列化、<code>HashMap.getNode</code>、<code>String.hashCode</code> 这类方法 self 高，就是热点本身。</li>
                  <li>点开它看调用者树，一层层往上找到业务代码，就知道是谁在大量调用它。</li>
                </ul>
              </div>
            </span>
            <span class="seg-item">
              <button :class="{ on: sort === 'inclusive' }" @click="setSort('inclusive')">按 inclusive</button>
              <div class="pop" role="tooltip">
                <h4><span class="pop-tag incl">inclusive</span>哪条调用链最贵</h4>
                <p>统计方法出现在<b>栈上任何位置</b>的样本，包括它调用的所有下游。</p>
                <p>适合回答「哪个业务入口或哪段流程最耗 CPU」，但排在前面的通常是框架的<b>过路帧</b>，例如：</p>
                <ul>
                  <li><code>DirectMethodHandleAccessor.invoke</code> inclusive 很高，self 很低</li>
                  <li><code>Method.invoke</code>、<code>OncePerRequestFilter.doFilter</code> inclusive 很高，self 几乎为 0</li>
                </ul>
                <p>这些是反射、AOP、Dubbo/Feign 代理，几乎所有请求都会经过，inclusive 自然高，但本身不耗 CPU，不是元凶。</p>
                <p class="pop-tip">用法：跳过框架帧，找第一个属于你业务包的方法。它的 inclusive 高，说明这个业务流程整体最贵。</p>
              </div>
            </span>
          </div>
          <label class="search">
            <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M8.5 3a5.5 5.5 0 1 0 3.36 9.86l3.64 3.64 1.06-1.06-3.64-3.64A5.5 5.5 0 0 0 8.5 3Zm0 1.5a4 4 0 1 1 0 8 4 4 0 0 1 0-8Z" /></svg>
            <input v-model="query" placeholder="搜索方法，例如 indexOf 或 LinkedList.contains" @input="scheduleHotspots" />
            <button v-if="query" type="button" class="clear" @click="clearQuery">×</button>
          </label>
          <button v-if="range" type="button" class="range-filter" title="取消区间（Esc）" @click="selectRange(null)">
            仅统计 {{ rangeText }}<span class="x">×</span>
          </button>
          <span class="spacer"></span>
          <span v-if="loading" class="busy"><span class="spinner"></span>加载中</span>
          <HintTip v-if="!exactPaths" tone="legacy" label="旧版报告" heading="旧版解析的局限">
            <p>调用者树二层以下<b>按方法名聚合</b>，反映的是该方法的全局调用分布，不是当前这条链；重载方法也被合并统计，且没有 CPU 趋势。</p>
            <p class="hint-note">重新上传 .jfr 即可得到按路径精确统计的结果。</p>
          </HintTip>
          <HintTip v-if="truncatedRatio >= 5" tone="warn" :label="`${truncatedRatio.toFixed(0)}% 调用栈被截断`" heading="调用栈超过 JFR 的 stackdepth">
            <p>
              <b>{{ truncatedRatio.toFixed(0) }}%</b> 的样本调用栈超过 stackdepth（默认 64 帧），JFR 只保留了栈顶的 64 帧。
            </p>
            <ul>
              <li><b>self 不受影响</b>：栈顶帧始终保留。</li>
              <li>靠近线程入口的方法（过滤器、<code>Thread.run</code> 等）<b>inclusive 会偏低</b>，调用者树也会在截断处停止。</li>
            </ul>
            <p class="hint-note">录制时加 <code>-XX:FlightRecorderOptions:stackdepth=256</code> 可避免。</p>
          </HintTip>
          <span class="muted">{{ range ? '区间内 ' : '' }}{{ totalSamples.toLocaleString() }} 个样本 · {{ hotspots.length }} 个方法</span>
          <HintTip v-if="exactPaths" heading="调用者树怎么看" :width="420">
            <p>点击方法展开<b>调用者</b>：按完整调用栈逐条路径统计，每个节点的百分比是它在<b>这条链</b>上占父节点样本的比例，沿链相乘就是占根方法的比例。</p>
            <p v-if="sort === 'self'">按 self 时，树只统计方法在栈顶执行的样本。</p>
            <p v-else>按 inclusive 时，树统计方法出现在栈上的所有样本。</p>
            <p>鼠标悬停节点可看样本数和占全部样本的比例；「≈ x 核」是该方法估算的平均 CPU 占用。</p>
            <p v-if="rangeSupported" class="hint-note">在上方 CPU 图上按住拖动框选时间区间，列表和调用者树只统计这段时间。</p>
          </HintTip>
        </div>

        <div class="hot-list" :class="{ dim: loading }" @mouseleave="previewKey = null">
          <div v-for="row in hotspots" :key="row.methodKey" class="method">
            <div
              class="method-line"
              :class="{ hit: row.matched, leaf: row.callers.length === 0, focused: cpu && focusRow?.methodKey === row.methodKey, pinned: cpu && pinnedKey === row.methodKey }"
              :title="row.methodKey"
              @mouseenter="previewKey = row.methodKey"
              @click="selectRow(row)"
            >
              <span class="chevron">{{ row.callers.length === 0 ? '' : opened[row.methodKey] ? '▾' : '▸' }}</span>
              <span class="pkg">{{ methodParts(row.methodKey).pkg }}</span><span class="method-name">{{ methodParts(row.methodKey).name }}</span><span class="params">{{ methodParts(row.methodKey).params }}</span>
              <span class="badge" :class="percentTone(primary(row))">{{ primary(row).toFixed(2) }}%</span>
              <span class="sub">{{ sort === 'self' ? 'inclusive' : 'self' }} {{ secondary(row).toFixed(2) }}%</span>
              <span v-if="coresLabel(row)" class="cores" title="估算的平均 CPU 占用：每段时间的 JVM CPU × 该方法的采样占比">≈ {{ coresLabel(row) }}</span>
              <span class="bar"><i :class="percentTone(primary(row))" :style="{ width: `${(primary(row) / maxPrimary) * 100}%` }"></i></span>
            </div>
            <CallerTree
              v-if="opened[row.methodKey] && row.callers.length"
              :key="`${row.methodKey}|${treeScope}`"
              :callers="row.callers"
              :depth="1"
              :path="[row.methodKey]"
              :basis="row.basis"
            />
          </div>
          <p v-if="!loading && hotspots.length === 0" class="empty">没有匹配的方法。</p>
        </div>
      </section>

      <JfrThreadsPanel v-if="threadsVisited" v-show="tab === 'threads'" :report-id="reportId" />

      <section v-if="tab === 'dumps'" class="card dumps">
        <div class="dump-hero">
          <div class="dump-count">{{ report?.threadDumpCount ?? 0 }}</div>
          <div>
            <h2>录制里的线程 Dump</h2>
            <p v-if="report && report.threadDumpCount === 0">
              这份录制没有 jdk.ThreadDump 事件。录制时加上 <code>jdk.ThreadDump#period=10s</code> 才会周期性抓取。
            </p>
            <p v-else-if="report && report.threadDumpCount === 1">
              只有 1 段线程 Dump，能看线程状态、锁和线程池，但做不了多 Dump 对比。录制时把 thread-dump 间隔调到 10s 或 1s 可以拿到连续快照。
            </p>
            <p v-else-if="report">
              已从 jdk.ThreadDump 抽出 {{ report.threadDumpCount }} 段 Dump，按时间排好，可以直接做多 Dump 对比、看线程状态变化和卡住的线程。
            </p>
            <button class="primary" :disabled="!report?.dumpAnalysisId" @click="openDump">打开线程分析 →</button>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.report-page {
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--ts-bg-primary);
  color: var(--ts-text-primary);
}
.report-head {
  flex: none;
  padding: 14px 28px 0;
  background: var(--ts-bg-secondary);
  border-bottom: var(--ts-border);
}
.back {
  border: 0;
  background: transparent;
  padding: 0;
  margin-bottom: 10px;
  color: var(--ts-text-secondary);
  font-size: 12.5px;
  cursor: pointer;
}
.back:hover { color: var(--ts-accent); }
.title-row { display: flex; align-items: center; gap: 14px; min-width: 0; }
.file-badge {
  flex: none;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.04em;
  color: #fff;
  background: linear-gradient(135deg, #2563eb, #7c3aed);
  box-shadow: 0 4px 10px rgba(37, 99, 235, 0.25);
}
.title-block { min-width: 0; flex: 1; }
.title-block h1 {
  margin: 0;
  font-size: 17px;
  font-weight: 650;
  line-height: 1.35;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.meta { display: flex; flex-wrap: wrap; gap: 6px 16px; margin-top: 4px; font-size: 12.5px; color: var(--ts-text-secondary); }
.meta-item b { color: var(--ts-text-primary); font-weight: 650; margin-right: 4px; font-variant-numeric: tabular-nums; }
.meta-item.jvm { padding: 0 8px; border-radius: 999px; background: var(--ts-bg-inset); max-width: 360px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tabs { display: flex; gap: 6px; margin-top: 12px; }
.tabs button {
  position: relative;
  border: 0;
  background: transparent;
  padding: 9px 14px 11px;
  cursor: pointer;
  color: var(--ts-text-secondary);
  font: inherit;
  font-size: 13.5px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: color var(--ts-transition);
}
.tabs button::after {
  content: '';
  position: absolute;
  left: 10px;
  right: 10px;
  bottom: -1px;
  height: 2px;
  border-radius: 2px;
  background: var(--ts-accent);
  transform: scaleX(0);
  transition: transform var(--ts-transition);
}
.tabs button:hover { color: var(--ts-text-primary); }
.tabs button.on { color: var(--ts-text-primary); font-weight: 650; }
.tabs button.on::after { transform: scaleX(1); }
.tab-count { font-size: 11px; font-weight: 600; background: var(--ts-bg-inset); color: var(--ts-text-secondary); border-radius: 999px; padding: 0 7px; line-height: 18px; }
.report-scroll {
  flex: 1;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 18px 28px 56px;
}
.error { color: var(--ts-danger); padding: 8px 28px 0; margin: 0; }
.muted { color: var(--ts-text-secondary); font-size: 12.5px; white-space: nowrap; }
.spacer { flex: 1; }

.card { background: var(--ts-bg-surface); border: var(--ts-border); border-radius: 14px; box-shadow: var(--ts-shadow-xs); padding: 16px 18px; }

.hot-tools { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.seg { display: inline-flex; background: var(--ts-bg-inset); border-radius: 9px; padding: 3px; }
.seg button { border: 0; background: transparent; padding: 5px 12px; border-radius: 7px; font: inherit; font-size: 12.5px; color: var(--ts-text-secondary); cursor: pointer; }
.seg button.on { background: var(--ts-bg-surface); color: var(--ts-text-primary); font-weight: 600; box-shadow: var(--ts-shadow-sm); }
.seg-item { position: relative; display: inline-flex; }
.pop {
  position: absolute;
  top: calc(100% + 10px);
  left: -3px;
  z-index: 30;
  width: 420px;
  padding: 14px 16px;
  background: #fff;
  border: var(--ts-border);
  border-radius: 12px;
  box-shadow: 0 16px 40px -12px rgba(15, 23, 42, 0.28);
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--ts-text-secondary);
  opacity: 0;
  visibility: hidden;
  transform: translateY(-4px);
  transition: opacity 150ms ease, transform 150ms ease, visibility 0s linear 150ms;
  pointer-events: none;
}
.pop::before {
  content: '';
  position: absolute;
  top: -6px;
  left: 28px;
  width: 10px;
  height: 10px;
  background: #fff;
  border-left: var(--ts-border);
  border-top: var(--ts-border);
  transform: rotate(45deg);
}
.seg-item:hover .pop {
  opacity: 1;
  visibility: visible;
  transform: none;
  transition-delay: 900ms, 900ms, 900ms;
}
.seg-item:active .pop { opacity: 0; visibility: hidden; transition: none; }
.pop h4 { display: flex; align-items: center; gap: 8px; margin: 0 0 6px; font-size: 13.5px; color: var(--ts-text-primary); }
.pop-tag { font-size: 11px; font-weight: 700; padding: 1px 8px; border-radius: 999px; font-family: var(--ts-font-display); }
.pop-tag.self { background: #fce7f3; color: #be185d; }
.pop-tag.incl { background: #ffedd5; color: #c2410c; }
.pop p { margin: 0 0 6px; }
.pop b { color: var(--ts-text-primary); }
.pop .pop-label { margin-bottom: 2px; }
.pop ul { margin: 0 0 6px; padding-left: 18px; }
.pop li { margin: 2px 0; }
.pop code { font-family: var(--ts-font-display); font-size: 11.5px; background: var(--ts-bg-inset); color: var(--ts-text-primary); padding: 0 4px; border-radius: 4px; word-break: break-all; }
.pop .pop-tip { margin: 8px 0 0; padding: 8px 10px; border-radius: 8px; background: var(--ts-accent-light); color: #1e40af; }
.search { position: relative; display: flex; align-items: center; }
.search svg { position: absolute; left: 10px; width: 15px; height: 15px; fill: var(--ts-text-muted); }
.search input { width: 360px; border: var(--ts-border); border-radius: 9px; padding: 7px 28px 7px 32px; font: inherit; font-size: 13px; background: var(--ts-bg-surface); color: var(--ts-text-primary); outline: none; transition: border-color var(--ts-transition), box-shadow var(--ts-transition); }
.search input:focus { border-color: var(--ts-accent); box-shadow: var(--ts-shadow-ring); }
.clear { position: absolute; right: 6px; border: 0; background: transparent; color: var(--ts-text-muted); font-size: 16px; cursor: pointer; line-height: 1; padding: 2px 4px; }
.clear:hover { color: var(--ts-text-primary); }
.busy { display: inline-flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--ts-text-secondary); }
.spinner { width: 13px; height: 13px; border-radius: 50%; border: 2px solid #dbe3f0; border-top-color: var(--ts-accent); animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.hot-tools { margin-bottom: 10px; }
.range-filter {
  display: inline-flex; align-items: center; gap: 6px; padding: 3px 6px 3px 10px;
  border: 1px solid #bfdbfe; border-radius: 999px; background: #eff6ff; color: #1d4ed8;
  font: inherit; font-size: 12px; font-weight: 600; font-variant-numeric: tabular-nums; cursor: pointer; white-space: nowrap;
  transition: background var(--ts-transition);
}
.range-filter:hover { background: #dbeafe; }
.range-filter .x { display: inline-grid; place-items: center; width: 16px; height: 16px; border-radius: 50%; background: #bfdbfe; font-size: 12px; line-height: 1; }
.cpu-card { margin-bottom: 16px; }
.hot-list { transition: opacity var(--ts-transition); }
.hot-list.dim { opacity: 0.55; }

.method { padding: 1px 0; }
.method-line {
  display: flex;
  align-items: center;
  min-width: 0;
  white-space: nowrap;
  min-height: 30px;
  padding: 4px 6px;
  border-radius: 6px;
  cursor: pointer;
  transition: background var(--ts-transition);
}
.method-line.leaf { cursor: default; }
.method-line:hover { background: var(--ts-bg-hover); }
.method-line.hit { background: var(--ts-accent-light); }
.method-line.focused { box-shadow: inset 3px 0 0 #fdba74; background: #fff7ed; }
.method-line.pinned { box-shadow: inset 3px 0 0 #ea580c; }
.method-line > .pkg, .method-line > .method-name, .method-line > .params, .method-line > .sub { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.method-line > .pkg { flex: 0 1 auto; }
.method-line > .method-name { flex: none; max-width: 60%; }
.params { flex: 0 1 auto; color: var(--ts-text-muted); font-family: var(--ts-font-display); font-size: 11.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; max-width: 420px; }
.method-line > .sub { flex: none; }
.hot-list { container-type: inline-size; }
@container (max-width: 820px) {
  .bar { display: none; }
}
@container (max-width: 600px) {
  .method-line > .sub { display: none; }
}
.cores { margin-left: 8px; padding: 0 6px; border-radius: 4px; background: #fff7ed; color: #c2410c; font-size: 11px; font-weight: 600; line-height: 18px; font-variant-numeric: tabular-nums; white-space: nowrap; flex: none; }
.chevron { width: 16px; flex: none; color: var(--ts-text-muted); font-size: 12px; }
.pkg { color: var(--ts-text-primary); font-family: var(--ts-font-display); font-size: 13px; }
.method-name { color: var(--ts-accent); font-weight: 700; font-family: var(--ts-font-display); font-size: 13px; }
.badge {
  flex: none;
  margin-left: 8px;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 11px;
  line-height: 18px;
  font-weight: 700;
  color: #fff;
  font-variant-numeric: tabular-nums;
}
.badge.hot { background: #db2777; }
.badge.warm { background: #ea580c; }
.badge.mid { background: #d97706; }
.badge.cool { background: #ca8a04; }
.sub { margin-left: 8px; color: var(--ts-text-muted); font-size: 11px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.bar { margin-left: auto; padding-left: 16px; align-self: center; flex: none; width: 140px; }
.bar i { display: block; height: 4px; border-radius: 4px; min-width: 2px; }
.bar i.hot { background: #f9a8d4; }
.bar i.warm { background: #fdba74; }
.bar i.mid { background: #fcd34d; }
.bar i.cool { background: #fde68a; }
.empty { text-align: center; color: var(--ts-text-muted); padding: 28px 0; margin: 0; }

.dump-hero { display: flex; gap: 22px; align-items: flex-start; padding: 8px 4px; }
.dump-count {
  flex: none;
  width: 72px;
  height: 72px;
  border-radius: 16px;
  display: grid;
  place-items: center;
  font-size: 30px;
  font-weight: 750;
  color: var(--ts-accent);
  background: var(--ts-accent-light);
  font-variant-numeric: tabular-nums;
}
.dump-hero h2 { margin: 2px 0 6px; font-size: 16px; }
.dump-hero p { margin: 0 0 14px; color: var(--ts-text-secondary); font-size: 13px; max-width: 720px; line-height: 1.65; }
.dump-hero code { font-family: var(--ts-font-display); font-size: 12px; background: var(--ts-bg-inset); padding: 1px 6px; border-radius: 4px; color: var(--ts-text-primary); }
.primary { border: 0; background: var(--ts-accent); color: #fff; border-radius: 9px; padding: 8px 16px; font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; box-shadow: 0 2px 6px rgba(37, 99, 235, 0.25); transition: filter var(--ts-transition); }
.primary:hover { filter: brightness(1.06); }
.primary:disabled { opacity: 0.45; cursor: default; box-shadow: none; }
</style>
