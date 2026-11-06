<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue'
import {
  fetchJfrAllocationCallers,
  fetchJfrAllocations,
  fetchJfrMemory,
  type JfrAllocationList,
  type JfrAllocationQuery,
  type JfrAllocationRow,
  type JfrAllocationSort,
  type JfrAllocationSource,
  type JfrAllocationSourceName,
  type JfrBucketRange,
  type JfrMemoryOverview,
} from '@/api/threadscope'
import HintTip from '@/components/common/HintTip.vue'
import CallerTree from '@/components/jfr/CallerTree.vue'
import JfrMemoryTrend, { type MemoryTrendMethod } from '@/components/jfr/JfrMemoryTrend.vue'
import JfrStallPanel from '@/components/jfr/JfrStallPanel.vue'
import CodeSettingsModal from '@/components/settings/CodeSettingsModal.vue'
import { useSettingsStore } from '@/stores/settingsStore'
import { formatBytes, formatNanos, formatRate } from '@/utils/bytes'
import { methodParts } from '@/utils/methodKey'

const props = defineProps<{ reportId: string; active: boolean }>()

const settings = useSettingsStore()

const SOURCE_LABEL: Record<JfrAllocationSourceName, string> = { tlab: 'TLAB 事件', sample: '分配采样' }
const SORT_LABEL: Record<JfrAllocationSort, string> = { business: '业务代码', self: '分配点', inclusive: '调用链' }

const overview = ref<JfrMemoryOverview | null>(null)
const legacy = ref(false)
const loadingOverview = ref(true)
const errorMsg = ref('')

const source = ref<JfrAllocationSourceName | null>(null)
const sort = ref<JfrAllocationSort>('business')
const query = ref('')
const type = ref<string | null>(null)
const range = ref<JfrBucketRange | null>(null)
const list = ref<JfrAllocationList | null>(null)
const loading = ref(false)
const opened = ref<Record<string, boolean>>({})
const pinnedKey = ref<string | null>(null)
const previewKey = ref<string | null>(null)
const openPools = ref<Record<string, boolean>>({})
/** 统计口径变了，调用者树里按需加载过的节点都要作废 */
const treeScope = ref('')
const totalBytes = ref(0)
const stallThread = ref('')
const stallAnchor = ref<HTMLDivElement | null>(null)

function showStalls(name: string) {
  stallThread.value = stallThread.value === name ? '' : name
  stallAnchor.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

let seq = 0
let queryTimer = 0

const packages = computed(() => settings.effectiveBusinessPackages)

function currentQuery(): JfrAllocationQuery {
  return { source: source.value, q: query.value.trim(), sort: sort.value, type: type.value, range: range.value, pkgs: packages.value }
}

provide('jfrLoadCallers', (path: string[], basis: JfrAllocationSort) =>
  fetchJfrAllocationCallers(props.reportId, path, basis, currentQuery()))
provide('jfrTotalSamples', totalBytes)
provide('jfrFormatWeight', (value: number) => formatBytes(value))

const sources = computed(() => overview.value?.sources ?? [])
const activeSource = computed<JfrAllocationSource | null>(() =>
  sources.value.find(s => s.source === source.value) ?? sources.value[0] ?? null)

async function loadOverview() {
  loadingOverview.value = true
  errorMsg.value = ''
  try {
    const data = await fetchJfrMemory(props.reportId)
    legacy.value = data == null
    overview.value = data
    source.value = data?.sources[0]?.source ?? null
    if (data?.sources.length) await loadList()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '内存数据加载失败'
  } finally {
    loadingOverview.value = false
  }
}

async function loadList() {
  if (!source.value) return
  const current = ++seq
  loading.value = true
  errorMsg.value = ''
  try {
    const data = await fetchJfrAllocations(props.reportId, currentQuery())
    if (current !== seq) return
    list.value = data
    totalBytes.value = data.totalBytes
    treeScope.value = `${data.source}|${data.sort}|${data.type ?? ''}|${data.rangeFrom ?? ''}-${data.rangeTo ?? ''}|${query.value.trim()}|${packages.value.join(',')}`
    previewKey.value = null
    if (!data.methods.some(r => r.methodKey === pinnedKey.value)) pinnedKey.value = null
    opened.value = {}
    for (const row of data.methods) if (row.expand) opened.value[row.methodKey] = true
  } catch (e) {
    if (current !== seq) return
    errorMsg.value = e instanceof Error ? e.message : '分配热点加载失败'
  } finally {
    if (current === seq) loading.value = false
  }
}

function setSource(value: JfrAllocationSourceName) {
  if (source.value === value) return
  source.value = value
  type.value = null
  range.value = null
  loadList()
}

function setSort(value: JfrAllocationSort) {
  if (sort.value === value) return
  sort.value = value
  loadList()
}

function setType(value: string | null) {
  type.value = type.value === value ? null : value
  loadList()
}

function selectRange(next: JfrBucketRange | null) {
  if (next?.from === range.value?.from && next?.to === range.value?.to) return
  range.value = next
  loadList()
}

function scheduleList() {
  window.clearTimeout(queryTimer)
  queryTimer = window.setTimeout(loadList, 250)
}

function clearQuery() {
  query.value = ''
  loadList()
}

watch(packages, () => {
  if (sort.value === 'business') loadList()
})

function onKey(event: KeyboardEvent) {
  if (event.key === 'Escape' && props.active && range.value) selectRange(null)
}

onMounted(() => {
  window.addEventListener('keydown', onKey)
  settings.loadCodeLayerConfig()
  loadOverview()
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  window.clearTimeout(queryTimer)
})

const bucketMs = computed(() => {
  const o = overview.value
  return o ? (o.endEpochMs - o.startEpochMs) / Math.max(1, o.bucketCount) : 1000
})

/** 方法列表的速率按统计范围的时长换算：选了区间用区间，否则用分配事件实际覆盖的时间 */
const scopeSeconds = computed(() => {
  const r = range.value
  if (r) return Math.max(1, ((r.to - r.from) * bucketMs.value) / 1000)
  const s = activeSource.value
  if (s?.coverStartEpochMs != null && s.coverEndEpochMs != null) {
    return Math.max(1, (s.coverEndEpochMs - s.coverStartEpochMs + bucketMs.value) / 1000)
  }
  const o = overview.value
  return o ? Math.max(1, (o.endEpochMs - o.startEpochMs) / 1000) : 1
})

const windowSeconds = computed(() => {
  const o = overview.value
  return o ? Math.max(1, (o.endEpochMs - o.startEpochMs) / 1000) : 1
})

function primaryPercent(row: JfrAllocationRow): number {
  if (row.basis === 'business') return row.attributedPercent ?? 0
  return row.basis === 'self' ? row.selfPercent : row.inclusivePercent
}

function primaryBytes(row: JfrAllocationRow): number {
  if (row.basis === 'business') return row.attributedBytes ?? 0
  return row.basis === 'self' ? row.selfBytes : row.inclusiveBytes
}

function secondary(row: JfrAllocationRow): string {
  if (row.basis === 'inclusive') return `分配点 ${row.selfPercent.toFixed(2)}%`
  return `调用链 ${row.inclusivePercent.toFixed(2)}%`
}

const maxPrimary = computed(() => Math.max(0.01, ...(list.value?.methods ?? []).map(primaryPercent)))

function tone(percent: number): string {
  if (percent >= 50) return 'hot'
  if (percent >= 20) return 'warm'
  if (percent >= 5) return 'mid'
  return 'cool'
}

function shortType(name: string | null): string {
  if (!name) return '?'
  const dot = name.lastIndexOf('.', name.indexOf('[') < 0 ? name.length : name.indexOf('['))
  return dot < 0 ? name : name.slice(dot + 1)
}

function toggle(row: JfrAllocationRow) {
  pinnedKey.value = row.methodKey
  opened.value[row.methodKey] = !opened.value[row.methodKey]
}

const focusRow = computed(() => {
  const rows = list.value?.methods ?? []
  const key = previewKey.value ?? pinnedKey.value
  return rows.find(r => r.methodKey === key) ?? rows[0] ?? null
})

const focusMethod = computed<MemoryTrendMethod | null>(() => {
  const row = focusRow.value
  if (!row) return null
  const parts = methodParts(row.methodKey)
  return { pkg: parts.pkg, name: parts.name + parts.params, basisLabel: SORT_LABEL[row.basis], timeline: row.timeline }
})

const focusMode = computed(() => {
  const key = focusRow.value?.methodKey
  if (key != null && key === previewKey.value && key !== pinnedKey.value) return 'preview'
  return key != null && key === pinnedKey.value ? 'pinned' : 'default'
})

const rangeText = computed(() => {
  const r = range.value
  const o = overview.value
  if (!r || !o) return ''
  const at = (i: number) => new Date(o.startEpochMs + i * bucketMs.value).toLocaleTimeString([], { hour12: false })
  return `${at(r.from)} – ${at(r.to)}`
})

/** 未配置业务包时，从归因结果里推荐包前缀（取前 3 段） */
const suggestedPackages = computed(() => {
  if (!settings.businessInferred || sort.value !== 'business') return []
  const weight = new Map<string, number>()
  for (const row of list.value?.methods ?? []) {
    const hash = row.methodKey.indexOf('#')
    const cls = hash < 0 ? row.methodKey : row.methodKey.slice(0, hash)
    const parts = cls.split('.')
    if (parts.length < 3) continue
    const pkg = parts.slice(0, Math.min(3, parts.length - 1)).join('.')
    weight.set(pkg, (weight.get(pkg) ?? 0) + (row.attributedBytes ?? 0))
  }
  return [...weight.entries()].sort((a, b) => b[1] - a[1]).slice(0, 4).map(([pkg]) => pkg)
})

function usePackage(pkg: string) {
  const profile = settings.activeProfile
  if (profile.businessPackages.includes(pkg)) return
  settings.saveProfile({ ...profile, businessPackages: [...profile.businessPackages, pkg] })
}

const unattributedPercent = computed(() => {
  const l = list.value
  if (!l || l.unattributedBytes == null || l.totalBytes <= 0) return 0
  return (l.unattributedBytes * 100) / l.totalBytes
})

const kpis = computed(() => {
  const o = overview.value
  if (!o) return null
  const gcCount = o.collectors.reduce((s, c) => s + c.count, 0)
  const pause = o.collectors.reduce((s, c) => s + c.pauseNanos, 0)
  const longest = o.collectors.reduce((s, c) => Math.max(s, c.longestPauseNanos), 0)
  const floors = o.heapFloor.filter((v): v is number => v != null)
  const floorPeak = floors.length ? Math.max(...floors) : null
  const peaks = o.heapPeak.filter((v): v is number => v != null)
  return {
    measuredRate: o.measuredTotal != null ? o.measuredTotal / windowSeconds.value : null,
    estimated: activeSource.value,
    gcCount,
    pause,
    longest,
    floorPeak,
    heapPeak: peaks.length ? Math.max(...peaks) : null,
  }
})

function coverSeconds(s: JfrAllocationSource): number {
  return s.coverStartEpochMs != null && s.coverEndEpochMs != null ? Math.round((s.coverEndEpochMs - s.coverStartEpochMs) / 1000) : 0
}

function percentOf(part: number | null | undefined, total: number | null | undefined): string {
  if (part == null || !total) return ''
  return `${((part * 100) / total).toFixed(0)}%`
}

const FINDING_ICON = { critical: '!', warn: '!', info: 'i' } as const
</script>

<template>
  <div class="memory">
    <p v-if="errorMsg" class="error">{{ errorMsg }}</p>
    <section v-if="loadingOverview" class="card empty-card"><span class="spinner"></span>正在加载内存数据…</section>

    <section v-else-if="legacy" class="card empty-card">
      这份报告由旧版解析生成，没有内存数据。重新上传 .jfr 即可得到分配热点、GC 和堆内存分析。
    </section>

    <template v-else-if="overview">
      <section v-if="overview.findings.length" class="findings">
        <div v-for="(f, k) in overview.findings" :key="k" class="finding" :class="f.level">
          <i class="f-icon">{{ FINDING_ICON[f.level] }}</i>
          <div>
            <b>{{ f.title }}</b>
            <p>{{ f.detail }}</p>
          </div>
        </div>
      </section>

      <section v-if="kpis" class="kpis">
        <div class="kpi">
          <span class="k-label">实际分配速率</span>
          <span class="k-value">{{ kpis.measuredRate != null ? formatRate(kpis.measuredRate) : '—' }}</span>
          <span class="k-sub">{{ kpis.measuredRate != null ? `录制期间共 ${formatBytes(overview.measuredTotal)}` : '没有 ThreadAllocationStatistics' }}</span>
        </div>
        <div class="kpi">
          <span class="k-label">事件估算（{{ kpis.estimated ? SOURCE_LABEL[kpis.estimated.source] : '无' }}）</span>
          <span class="k-value">{{ kpis.estimated ? formatBytes(kpis.estimated.estimatedBytes) : '—' }}</span>
          <span class="k-sub">{{ kpis.estimated ? `${kpis.estimated.events.toLocaleString()} 个事件 · 覆盖 ${coverSeconds(kpis.estimated)} 秒` : '录制里没有分配调用栈' }}</span>
        </div>
        <div class="kpi">
          <span class="k-label">GC</span>
          <span class="k-value">{{ kpis.gcCount }} 次</span>
          <span class="k-sub">停顿共 {{ formatNanos(kpis.pause) }}，最长 {{ formatNanos(kpis.longest) }}</span>
        </div>
        <div class="kpi" :class="{ bad: overview.stallCount > 0 }">
          <span class="k-label">分配停顿</span>
          <span class="k-value">{{ overview.stallCount }} 次</span>
          <span class="k-sub">{{ overview.stallCount ? `累计 ${formatNanos(overview.stallNanos)}，最长 ${formatNanos(overview.longestStallNanos)}` : '没有线程因等待 GC 而阻塞' }}</span>
        </div>
        <div class="kpi" :class="{ bad: kpis.floorPeak != null && overview.heapMax != null && kpis.floorPeak > overview.heapMax * 0.85 }">
          <span class="k-label">GC 后堆占用峰值</span>
          <span class="k-value">{{ formatBytes(kpis.floorPeak) }}</span>
          <span class="k-sub">堆上限 {{ formatBytes(overview.heapMax) }}<template v-if="kpis.floorPeak != null && overview.heapMax"> · {{ percentOf(kpis.floorPeak, overview.heapMax) }}</template></span>
        </div>
        <div class="kpi">
          <span class="k-label">收集器</span>
          <span class="k-value small">{{ overview.youngCollector ?? '—' }}</span>
          <span class="k-sub">{{ overview.oldCollector ? `老年代 ${overview.oldCollector}` : '' }}</span>
        </div>
      </section>

      <section class="card trend-card">
        <JfrMemoryTrend
          :overview="overview"
          :source="activeSource"
          :method="focusMethod"
          :mode="focusMode"
          :range="range"
          :range-enabled="!!list?.rangeSupported"
          @select="selectRange"
        />
      </section>

      <div class="mem-grid">
        <section class="card alloc">
          <div class="card-title">
            <h2>分配热点</h2>
            <span class="muted">哪段代码分配了最多的内存</span>
          </div>

          <div v-if="!sources.length" class="no-stacks">
            <p>这份录制没有分配调用栈事件（ObjectAllocationSample / ObjectAllocationInNewTLAB），无法定位到方法。</p>
            <ul>
              <li>JDK 16+：用 default 模板就会采集 <code>jdk.ObjectAllocationSample</code>，确认没有被关闭。</li>
              <li>需要更精细的结果：<code>jcmd &lt;pid&gt; JFR.start settings=profile duration=120s filename=alloc.jfr</code>（开启 TLAB 分配事件）。</li>
            </ul>
            <p>右侧的线程池分配量来自 ThreadAllocationStatistics，不依赖调用栈。</p>
          </div>

          <template v-else>
            <div class="tools">
              <div v-if="sources.length > 1" class="seg">
                <button v-for="s in sources" :key="s.source" :class="{ on: activeSource?.source === s.source }" @click="setSource(s.source)">
                  {{ SOURCE_LABEL[s.source] }}
                </button>
              </div>
              <div class="seg">
                <button v-for="s in (['business', 'self', 'inclusive'] as const)" :key="s" :class="{ on: sort === s }" @click="setSort(s)">
                  按{{ SORT_LABEL[s] }}
                </button>
              </div>
              <HintTip heading="三种口径" :width="460">
                <p><b>按业务代码</b>（推荐）：每次分配从栈顶往下找<b>第一个业务方法</b>，字节数只记给它。<code>Arrays.copyOf</code>、<code>StringBuilder</code>、Jackson 这些 JDK / 框架里的分配都会归到真正发起它的业务代码上，所有方法加起来是 100%。</p>
                <p><b>按分配点</b>：只看栈顶，即真正执行 new 的方法，和 JMC 的「TLAB 分配」视图一致。</p>
                <p><b>按调用链</b>：方法出现在栈上任何位置都算，适合看某个入口或流程整体分配了多少；排在前面的通常是 Filter、反射这类过路帧。</p>
                <p class="hint-note">展开方法可以看到它的主要分配点（分配了什么类型）和调用者树。</p>
              </HintTip>
              <label class="search">
                <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M8.5 3a5.5 5.5 0 1 0 3.36 9.86l3.64 3.64 1.06-1.06-3.64-3.64A5.5 5.5 0 0 0 8.5 3Zm0 1.5a4 4 0 1 1 0 8 4 4 0 0 1 0-8Z" /></svg>
                <input v-model="query" placeholder="搜索方法，例如 OrderService 或 toJSONString" @input="scheduleList" />
                <button v-if="query" type="button" class="clear" @click="clearQuery">×</button>
              </label>
              <button v-if="type" type="button" class="filter-chip" title="取消类型过滤" @click="setType(null)">
                类型 {{ shortType(type) }}<span class="x">×</span>
              </button>
              <button v-if="range" type="button" class="filter-chip" title="取消区间（Esc）" @click="selectRange(null)">
                仅统计 {{ rangeText }}<span class="x">×</span>
              </button>
              <span class="spacer"></span>
              <span v-if="loading" class="busy"><span class="spinner"></span>加载中</span>
              <span v-if="list" class="muted">{{ formatBytes(list.totalBytes) }} · {{ list.methods.length }} 个方法</span>
            </div>

            <div v-if="sort === 'business'" class="pkg-line">
              <template v-if="settings.businessInferred">
                <span class="pkg-tag inferred">未配置业务包</span>
                <span>JDK 和常见框架之外的代码都按业务代码处理，公司内部的公共框架也会被算进来。</span>
                <template v-if="suggestedPackages.length">
                  <span class="muted">设为业务包：</span>
                  <button v-for="pkg in suggestedPackages" :key="pkg" type="button" class="pkg-suggest" @click="usePackage(pkg)">{{ pkg }}</button>
                </template>
              </template>
              <template v-else>
                <span class="pkg-tag">业务包</span>
                <code v-for="pkg in packages" :key="pkg">{{ pkg }}</code>
              </template>
              <button type="button" class="link" @click="settings.settingsOpen = true">设置</button>
              <span v-if="unattributedPercent >= 1" class="muted">· {{ unattributedPercent.toFixed(1) }}% 的分配整条栈都没有业务代码（GC、Netty、框架线程等）</span>
            </div>

            <div class="hot-list" :class="{ dim: loading }" @mouseleave="previewKey = null">
              <div v-for="row in list?.methods ?? []" :key="row.methodKey" class="method">
                <div
                  class="method-line"
                  :class="{ hit: row.matched, focused: focusRow?.methodKey === row.methodKey, pinned: pinnedKey === row.methodKey }"
                  :title="row.methodKey"
                  @mouseenter="previewKey = row.methodKey"
                  @click="toggle(row)"
                >
                  <span class="chevron">{{ opened[row.methodKey] ? '▾' : '▸' }}</span>
                  <span class="pkg">{{ methodParts(row.methodKey).pkg }}</span><span class="method-name">{{ methodParts(row.methodKey).name }}</span><span class="params">{{ methodParts(row.methodKey).params }}</span>
                  <span class="badge" :class="tone(primaryPercent(row))">{{ primaryPercent(row).toFixed(2) }}%</span>
                  <span class="bytes">{{ formatBytes(primaryBytes(row)) }} · ≈ {{ formatRate(primaryBytes(row) / scopeSeconds) }}</span>
                  <span class="sub">{{ secondary(row) }}</span>
                  <span class="bar"><i :class="tone(primaryPercent(row))" :style="{ width: `${(primaryPercent(row) / maxPrimary) * 100}%` }"></i></span>
                </div>
                <div v-if="opened[row.methodKey]" class="detail">
                  <div v-if="row.sites.length" class="sites">
                    <span class="sites-label">{{ row.basis === 'self' ? '分配类型' : '主要分配点' }}</span>
                    <button
                      v-for="site in row.sites"
                      :key="site.methodKey + site.type"
                      type="button"
                      class="site"
                      :title="`${site.methodKey}\n${site.type ?? ''}\n${formatBytes(site.bytes)}，点击按该类型过滤`"
                      @click.stop="site.type && setType(site.type)"
                    >
                      <template v-if="row.basis !== 'self'">{{ site.label }} · </template><b>{{ shortType(site.type) }}</b>
                      <em>{{ site.percent.toFixed(1) }}%</em>
                    </button>
                  </div>
                  <CallerTree
                    v-if="row.callers.length"
                    :key="`${row.methodKey}|${treeScope}`"
                    :callers="row.callers"
                    :depth="1"
                    :path="[row.methodKey]"
                    :basis="row.basis"
                  />
                </div>
              </div>
              <p v-if="!loading && list && list.methods.length === 0" class="empty">没有匹配的方法。</p>
            </div>
          </template>
        </section>

        <aside class="side">
          <section v-if="list?.types.length" class="card side-card">
            <div class="card-title">
              <h3>对象类型</h3>
              <span class="muted">点击只看该类型</span>
            </div>
            <button
              v-for="t in list.types.slice(0, 15)"
              :key="t.name"
              type="button"
              class="type-row"
              :class="{ on: type === t.name }"
              :title="t.name"
              @click="setType(t.name)"
            >
              <span class="t-name">{{ t.name }}</span>
              <span class="t-val">{{ formatBytes(t.bytes) }}</span>
              <span class="t-pct">{{ t.percent.toFixed(1) }}%</span>
              <span class="t-bar"><i :style="{ width: `${Math.min(100, t.percent)}%` }"></i></span>
            </button>
          </section>

          <section v-if="overview.pools.length" class="card side-card">
            <div class="card-title">
              <h3>线程池分配量</h3>
              <HintTip heading="实际分配量" :width="340">
                <p>来自 ThreadAllocationStatistics：每个线程分配计数器在录制期间的增量，是<b>实际值</b>，不受采样影响，default 模板也有。</p>
              </HintTip>
            </div>
            <div v-for="p in overview.pools" :key="p.name" class="pool">
              <button type="button" class="pool-row" @click="openPools[p.name] = !openPools[p.name]">
                <span class="chevron">{{ p.top.length ? (openPools[p.name] ? '▾' : '▸') : '' }}</span>
                <span class="p-name" :title="p.name">{{ p.name }}</span>
                <span class="p-threads">{{ p.threads }} 线程</span>
                <span class="p-val">{{ formatBytes(p.bytes) }}</span>
                <span class="p-rate">{{ formatRate(p.bytes / windowSeconds) }}</span>
              </button>
              <div v-if="openPools[p.name]" class="pool-threads">
                <div v-for="t in p.top" :key="t.name" class="pool-thread">
                  <span :title="t.name">{{ t.name }}</span><b>{{ formatBytes(t.bytes) }}</b>
                </div>
              </div>
            </div>
          </section>

          <section v-if="overview.stallThreads.length" class="card side-card">
            <div class="card-title">
              <h3>分配停顿最久的线程</h3>
              <span class="muted">点击看调用栈</span>
            </div>
            <button
              v-for="t in overview.stallThreads"
              :key="t.name"
              type="button"
              class="stall-row"
              :class="{ on: stallThread === t.name }"
              @click="showStalls(t.name)"
            >
              <span :title="t.name">{{ t.name }}</span>
              <em>{{ t.count }} 次</em>
              <b>{{ formatNanos(t.durationNanos) }}</b>
            </button>
          </section>

          <section v-if="overview.collectors.length" class="card side-card">
            <div class="card-title">
              <h3>GC 统计</h3>
            </div>
            <div v-for="c in overview.collectors" :key="c.name" class="gc-row">
              <b>{{ c.name }}</b>
              <span>{{ c.count }} 次 · 停顿 {{ formatNanos(c.pauseNanos) }} · 最长 {{ formatNanos(c.longestPauseNanos) }}</span>
              <span class="muted">GC 耗时合计 {{ formatNanos(c.durationNanos) }}（含并发阶段）</span>
            </div>
          </section>
        </aside>
      </div>

      <div v-if="overview.stallCount > 0" ref="stallAnchor">
        <JfrStallPanel
          v-model:thread="stallThread"
          :report-id="reportId"
          :range="range"
          :range-text="rangeText"
          :pkgs="packages"
          @clear-range="selectRange(null)"
        />
      </div>
    </template>

    <CodeSettingsModal />
  </div>
</template>

<style scoped>
.memory { display: flex; flex-direction: column; gap: 16px; }
.error { color: var(--ts-danger); margin: 0; }
.muted { color: var(--ts-text-secondary); font-size: 12.5px; white-space: nowrap; }
.spacer { flex: 1; }
.card { background: var(--ts-bg-surface); border: var(--ts-border); border-radius: 14px; box-shadow: var(--ts-shadow-xs); padding: 16px 18px; min-width: 0; }
.empty-card { display: flex; align-items: center; gap: 10px; color: var(--ts-text-secondary); font-size: 13px; }
.card-title { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.card-title h2 { margin: 0; font-size: 15px; font-weight: 650; }
.card-title h3 { margin: 0; font-size: 13.5px; font-weight: 650; }

.findings { display: flex; flex-direction: column; gap: 8px; }
.finding { display: flex; gap: 12px; align-items: flex-start; padding: 10px 14px; border-radius: 10px; border: 1px solid; font-size: 12.5px; line-height: 1.6; }
.finding b { font-size: 13px; }
.finding p { margin: 2px 0 0; color: var(--ts-text-secondary); }
.f-icon { flex: none; width: 20px; height: 20px; border-radius: 50%; display: grid; place-items: center; font-style: normal; font-weight: 800; font-size: 12px; color: #fff; margin-top: 1px; }
.finding.critical { background: #fef2f2; border-color: #fecaca; }
.finding.critical b { color: #991b1b; }
.finding.critical .f-icon { background: #dc2626; }
.finding.warn { background: #fffbeb; border-color: #fde68a; }
.finding.warn b { color: #92400e; }
.finding.warn .f-icon { background: #d97706; }
.finding.info { background: #f8fafc; border-color: #e2e8f0; }
.finding.info .f-icon { background: #64748b; }

.kpis { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px; }
@media (max-width: 1280px) { .kpis { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
.kpi { position: relative; display: flex; flex-direction: column; gap: 3px; padding: 12px 14px; background: var(--ts-bg-surface); border: var(--ts-border); border-radius: 12px; overflow: hidden; min-width: 0; }
.kpi::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: #cbd5e1; }
.kpi.bad::before { background: #dc2626; }
.kpi.bad .k-value { color: #b91c1c; }
.k-label { font-size: 12px; color: var(--ts-text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.k-value { font-size: 19px; font-weight: 700; font-variant-numeric: tabular-nums; color: var(--ts-text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.k-value.small { font-size: 15px; line-height: 27px; }
.k-sub { font-size: 11.5px; color: var(--ts-text-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.mem-grid { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 16px; align-items: start; }
@media (max-width: 1180px) { .mem-grid { grid-template-columns: minmax(0, 1fr); } }
.side { display: flex; flex-direction: column; gap: 16px; min-width: 0; }

.tools { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 10px; }
.seg { display: inline-flex; background: var(--ts-bg-inset); border-radius: 9px; padding: 3px; }
.seg button { border: 0; background: transparent; padding: 5px 12px; border-radius: 7px; font: inherit; font-size: 12.5px; color: var(--ts-text-secondary); cursor: pointer; white-space: nowrap; }
.seg button.on { background: var(--ts-bg-surface); color: var(--ts-text-primary); font-weight: 600; box-shadow: var(--ts-shadow-sm); }
.search { position: relative; display: flex; align-items: center; }
.search svg { position: absolute; left: 10px; width: 15px; height: 15px; fill: var(--ts-text-muted); }
.search input { width: 300px; border: var(--ts-border); border-radius: 9px; padding: 7px 28px 7px 32px; font: inherit; font-size: 13px; background: var(--ts-bg-surface); color: var(--ts-text-primary); outline: none; }
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

.pkg-line { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 8px; margin: -2px 0 10px; padding: 7px 10px; border-radius: 8px; background: var(--ts-bg-inset); font-size: 12px; color: var(--ts-text-secondary); }
.pkg-line code { font-family: var(--ts-font-display); font-size: 11.5px; background: var(--ts-bg-surface); padding: 0 6px; border-radius: 4px; color: var(--ts-text-primary); }
.pkg-tag { font-size: 11px; font-weight: 650; padding: 0 8px; border-radius: 999px; line-height: 18px; background: #dcfce7; color: #15803d; }
.pkg-tag.inferred { background: #fef3c7; color: #92400e; }
.pkg-suggest { border: 1px dashed #93c5fd; background: #fff; color: #1d4ed8; border-radius: 6px; padding: 0 7px; font: inherit; font-family: var(--ts-font-display); font-size: 11.5px; line-height: 20px; cursor: pointer; }
.pkg-suggest:hover { background: #eff6ff; }
.link { border: 0; background: transparent; padding: 0; color: var(--ts-accent); font: inherit; font-size: 12px; cursor: pointer; }
.link:hover { text-decoration: underline; }

.no-stacks { font-size: 13px; color: var(--ts-text-secondary); line-height: 1.7; }
.no-stacks p { margin: 0 0 6px; }
.no-stacks ul { margin: 0 0 6px; padding-left: 18px; }
.no-stacks code { font-family: var(--ts-font-display); font-size: 12px; background: var(--ts-bg-inset); padding: 1px 6px; border-radius: 4px; color: var(--ts-text-primary); }

.hot-list { transition: opacity var(--ts-transition); container-type: inline-size; }
.hot-list.dim { opacity: 0.55; }
.method { padding: 1px 0; }
.method-line { display: flex; align-items: center; min-width: 0; white-space: nowrap; min-height: 30px; padding: 4px 6px; border-radius: 6px; cursor: pointer; transition: background var(--ts-transition); }
.method-line:hover { background: var(--ts-bg-hover); }
.method-line.hit { background: var(--ts-accent-light); }
.method-line.focused { box-shadow: inset 3px 0 0 #fdba74; background: #fff7ed; }
.method-line.pinned { box-shadow: inset 3px 0 0 #ea580c; }
.method-line > .pkg, .method-line > .method-name, .method-line > .params, .method-line > .sub, .method-line > .bytes { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chevron { width: 16px; flex: none; color: var(--ts-text-muted); font-size: 12px; }
.pkg { flex: 0 1 auto; color: var(--ts-text-primary); font-family: var(--ts-font-display); font-size: 13px; }
.method-name { flex: none; max-width: 60%; color: var(--ts-accent); font-weight: 700; font-family: var(--ts-font-display); font-size: 13px; }
.params { flex: 0 1 auto; color: var(--ts-text-muted); font-family: var(--ts-font-display); font-size: 11.5px; max-width: 320px; }
.badge { flex: none; margin-left: 8px; padding: 0 6px; border-radius: 4px; font-size: 11px; line-height: 18px; font-weight: 700; color: #fff; font-variant-numeric: tabular-nums; }
.badge.hot { background: #db2777; }
.badge.warm { background: #ea580c; }
.badge.mid { background: #d97706; }
.badge.cool { background: #ca8a04; }
.bytes { flex: none; margin-left: 8px; padding: 0 6px; border-radius: 4px; background: #ecfeff; color: #0e7490; font-size: 11px; font-weight: 600; line-height: 18px; font-variant-numeric: tabular-nums; }
.sub { flex: none; margin-left: 8px; color: var(--ts-text-muted); font-size: 11px; font-variant-numeric: tabular-nums; }
.bar { margin-left: auto; padding-left: 16px; flex: none; width: 120px; }
.bar i { display: block; height: 4px; border-radius: 4px; min-width: 2px; }
.bar i.hot { background: #f9a8d4; }
.bar i.warm { background: #fdba74; }
.bar i.mid { background: #fcd34d; }
.bar i.cool { background: #fde68a; }
@container (max-width: 860px) { .bar { display: none; } }
@container (max-width: 680px) { .method-line > .sub { display: none; } }
.detail { padding: 2px 0 8px 22px; }
.sites { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin: 2px 0 6px; }
.sites-label { font-size: 11.5px; color: var(--ts-text-muted); margin-right: 2px; }
.site { display: inline-flex; align-items: center; gap: 4px; max-width: 100%; border: var(--ts-border); background: var(--ts-bg-surface); border-radius: 6px; padding: 1px 8px; font: inherit; font-family: var(--ts-font-display); font-size: 11.5px; color: var(--ts-text-secondary); cursor: pointer; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.site:hover { border-color: #93c5fd; background: #eff6ff; }
.site b { color: #0e7490; font-weight: 650; }
.site em { font-style: normal; color: var(--ts-text-primary); font-weight: 600; font-variant-numeric: tabular-nums; }
.empty { text-align: center; color: var(--ts-text-muted); padding: 28px 0; margin: 0; }

.side-card { padding: 14px 14px 10px; }
.type-row { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) auto 44px; gap: 8px; align-items: center; width: 100%; border: 0; background: transparent; padding: 5px 6px 7px; border-radius: 6px; font: inherit; font-size: 12px; text-align: left; cursor: pointer; }
.type-row:hover { background: var(--ts-bg-hover); }
.type-row.on { background: var(--ts-accent-light); }
.t-name { font-family: var(--ts-font-display); color: var(--ts-text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.t-val { color: var(--ts-text-secondary); font-variant-numeric: tabular-nums; }
.t-pct { text-align: right; font-weight: 650; font-variant-numeric: tabular-nums; }
.t-bar { position: absolute; left: 6px; right: 6px; bottom: 2px; height: 2px; }
.t-bar i { display: block; height: 100%; background: #67e8f9; border-radius: 2px; }
.pool + .pool { border-top: 1px solid #f1f5f9; }
.pool-row { display: grid; grid-template-columns: 14px minmax(0, 1fr) auto auto; grid-template-rows: auto auto; column-gap: 8px; width: 100%; border: 0; background: transparent; padding: 6px 4px; font: inherit; font-size: 12px; text-align: left; cursor: pointer; border-radius: 6px; }
.pool-row:hover { background: var(--ts-bg-hover); }
.pool-row .chevron { grid-row: span 2; width: auto; }
.p-name { font-weight: 600; color: var(--ts-text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.p-threads { color: var(--ts-text-muted); font-size: 11.5px; }
.p-val { grid-column: 3; grid-row: 1 / span 2; align-self: center; font-weight: 650; font-variant-numeric: tabular-nums; }
.p-rate { grid-column: 4; grid-row: 1 / span 2; align-self: center; color: var(--ts-text-muted); font-size: 11.5px; font-variant-numeric: tabular-nums; }
.p-threads { grid-column: 2; }
.pool-threads { padding: 0 4px 6px 26px; }
.pool-thread, .stall-row { display: flex; align-items: center; gap: 8px; font-size: 12px; line-height: 22px; min-width: 0; }
.pool-thread span, .stall-row span { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ts-text-secondary); font-family: var(--ts-font-display); font-size: 11.5px; }
.pool-thread b, .stall-row b { font-variant-numeric: tabular-nums; }
.stall-row { width: 100%; border: 0; background: transparent; padding: 0 6px; border-radius: 6px; font: inherit; font-size: 12px; text-align: left; cursor: pointer; }
.stall-row:hover { background: var(--ts-bg-hover); }
.stall-row.on { background: #fef2f2; }
.stall-row em { font-style: normal; color: var(--ts-text-muted); font-size: 11.5px; }
.stall-row b { color: #b91c1c; }
.gc-row { display: flex; flex-direction: column; gap: 1px; padding: 6px 2px; font-size: 12px; }
.gc-row + .gc-row { border-top: 1px solid #f1f5f9; }
.gc-row .muted { font-size: 11.5px; white-space: normal; }
</style>
