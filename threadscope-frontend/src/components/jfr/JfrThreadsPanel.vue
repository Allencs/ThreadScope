<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  fetchJfrThread,
  fetchJfrThreads,
  type JfrPlatformThread,
  type JfrThreadOverview,
  type JfrThreadPool,
  type JfrThreadRow,
} from '@/api/threadscope'
import JfrThreadTrend from '@/components/jfr/JfrThreadTrend.vue'

const props = defineProps<{ reportId: string }>()

const overview = ref<JfrThreadOverview | null>(null)
const loading = ref(true)
const errorMsg = ref('')
const selected = ref<string | null>(null)
const expanded = ref<Record<string, boolean>>({})
const showAll = ref<Record<string, boolean>>({})
const query = ref('')
const sortKey = ref<'total' | 'churn' | 'alloc' | 'name'>('total')
const onlyChanged = ref(false)
const detail = ref<JfrPlatformThread | null>(null)
const detailRow = ref<JfrThreadRow | null>(null)
const detailLoading = ref(false)

const ROW_LIMIT = 200
const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    overview.value = await fetchJfrThreads(props.reportId)
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '线程数据加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => props.reportId, load)

const span = computed(() => {
  const o = overview.value
  return o ? Math.max(1, o.windowEndEpochMs - o.windowStartEpochMs) : 1
})

const selectedPool = computed<JfrThreadPool | null>(() =>
  overview.value?.pools.find(p => p.name === selected.value) ?? null
)

const changedPools = computed(() =>
  (overview.value?.pools ?? [])
    .filter(p => p.created + p.ended > 0)
    .sort((a, b) => b.created + b.ended - (a.created + a.ended))
)

const poolCount = computed(() =>
  (overview.value?.pools ?? []).filter(p => p.type !== 'Standalone').length
)

const needle = computed(() => query.value.trim().toLowerCase())

function threadMatches(row: JfrThreadRow, q: string): boolean {
  return (row.name ?? '').toLowerCase().includes(q) || String(row.javaThreadId).includes(q)
}

function sortThreads(rows: JfrThreadRow[]): JfrThreadRow[] {
  if (sortKey.value === 'alloc') {
    return [...rows].sort((a, b) => (b.recordedAllocatedBytes ?? -1) - (a.recordedAllocatedBytes ?? -1)
      || collator.compare(a.name ?? '', b.name ?? ''))
  }
  const changed = (r: JfrThreadRow) => (r.existedBefore ? 0 : 1) + (r.aliveAtEnd ? 0 : 1)
  return [...rows].sort((a, b) => changed(b) - changed(a) || collator.compare(a.name ?? '', b.name ?? ''))
}

const allocMax = computed(() => Math.max(1, ...(overview.value?.pools ?? []).map(p => p.recordedAllocatedBytes)))

const visiblePools = computed(() => {
  const q = needle.value
  const list: { pool: JfrThreadPool; rows: JfrThreadRow[]; auto: boolean }[] = []
  for (const pool of overview.value?.pools ?? []) {
    if (onlyChanged.value && pool.created + pool.ended === 0) continue
    if (!q) {
      list.push({ pool, rows: pool.threads, auto: false })
      continue
    }
    const poolHit = pool.name.toLowerCase().includes(q) || pool.type.toLowerCase().includes(q)
    const rows = poolHit ? pool.threads : pool.threads.filter(r => threadMatches(r, q))
    if (rows.length) list.push({ pool, rows, auto: !poolHit })
  }
  const by = sortKey.value
  list.sort((a, b) => {
    if (by === 'name') return collator.compare(a.pool.name, b.pool.name)
    if (by === 'churn') {
      const d = b.pool.created + b.pool.ended - (a.pool.created + a.pool.ended)
      if (d !== 0) return d
    }
    if (by === 'alloc') {
      const d = b.pool.recordedAllocatedBytes - a.pool.recordedAllocatedBytes
      if (d !== 0) return d
    }
    return b.pool.total - a.pool.total || collator.compare(a.pool.name, b.pool.name)
  })
  return list
})

const matchedThreadCount = computed(() => visiblePools.value.reduce((n, v) => n + v.rows.length, 0))

function isOpen(name: string, auto: boolean): boolean {
  return expanded.value[name] ?? auto
}

function togglePool(pool: JfrThreadPool, auto: boolean) {
  expanded.value[pool.name] = !isOpen(pool.name, auto)
}

function selectPool(name: string | null) {
  selected.value = name
}

function shownRows(name: string, rows: JfrThreadRow[]): JfrThreadRow[] {
  const sorted = sortThreads(rows)
  return showAll.value[name] ? sorted : sorted.slice(0, ROW_LIMIT)
}

async function openThread(row: JfrThreadRow) {
  detailRow.value = row
  detail.value = null
  detailLoading.value = true
  try {
    detail.value = await fetchJfrThread(props.reportId, row.javaThreadId)
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '线程详情加载失败'
  } finally {
    detailLoading.value = false
  }
}

function closeDetail() {
  detailRow.value = null
  detail.value = null
}

const DRAWER_KEY = 'threadscope.jfr.drawerWidth'
const DRAWER_DEFAULT = 520
const DRAWER_MIN = 420
const drawerWidth = ref(clampDrawer(Number(localStorage.getItem(DRAWER_KEY)) || DRAWER_DEFAULT))
const resizing = ref(false)

function clampDrawer(width: number): number {
  return Math.round(Math.min(Math.max(width, DRAWER_MIN), window.innerWidth * 0.95))
}

function startResize(event: PointerEvent) {
  const handle = event.currentTarget as HTMLElement
  handle.setPointerCapture(event.pointerId)
  resizing.value = true
  const startX = event.clientX
  const startWidth = drawerWidth.value
  const move = (e: PointerEvent) => {
    drawerWidth.value = clampDrawer(startWidth + startX - e.clientX)
  }
  const stop = () => {
    resizing.value = false
    handle.removeEventListener('pointermove', move)
    handle.removeEventListener('pointerup', stop)
    handle.removeEventListener('pointercancel', stop)
    localStorage.setItem(DRAWER_KEY, String(drawerWidth.value))
  }
  handle.addEventListener('pointermove', move)
  handle.addEventListener('pointerup', stop)
  handle.addEventListener('pointercancel', stop)
}

function resetDrawer() {
  drawerWidth.value = clampDrawer(DRAWER_DEFAULT)
  localStorage.removeItem(DRAWER_KEY)
}

function lifecycle(row: { existedBefore: boolean; aliveAtEnd: boolean }): { label: string; tone: string } {
  if (!row.existedBefore && !row.aliveAtEnd) return { label: '新建 → 销毁', tone: 'both' }
  if (!row.existedBefore) return { label: '录制中新建', tone: 'created' }
  if (!row.aliveAtEnd) return { label: '录制中销毁', tone: 'ended' }
  return { label: '全程存活', tone: 'steady' }
}

function lifeBar(row: JfrThreadRow): { left: string; width: string } {
  const o = overview.value
  if (!o) return { left: '0%', width: '100%' }
  const from = row.existedBefore || row.startEpochMs == null ? 0 : (row.startEpochMs - o.windowStartEpochMs) / span.value
  const to = row.aliveAtEnd || row.endEpochMs == null ? 1 : (row.endEpochMs - o.windowStartEpochMs) / span.value
  const l = Math.max(0, Math.min(1, from))
  const r = Math.max(l, Math.min(1, to))
  return { left: `${(l * 100).toFixed(2)}%`, width: `${Math.max(1.5, (r - l) * 100).toFixed(2)}%` }
}

function sparkline(values: number[], w = 96, h = 22): string {
  if (values.length === 0) return ''
  if (values.length === 1) values = [values[0], values[0]]
  const lo = Math.min(...values)
  const hi = Math.max(...values)
  const range = Math.max(1, hi - lo)
  const stepX = values.length > 1 ? w / (values.length - 1) : 0
  return values
    .map((v, i) => `${i === 0 ? 'M' : 'L'}${(i * stepX).toFixed(1)},${(h - 2 - ((v - lo) / range) * (h - 4)).toFixed(1)}`)
    .join(' ')
}

function typeLabel(pool: JfrThreadPool): string {
  if (pool.type === 'Custom') return '自定义'
  if (pool.type === 'Standalone') return '单个线程'
  if (pool.type === 'JVM-Internal') return 'JVM'
  return pool.type
}

function clock(epoch: number | null | undefined): string {
  if (epoch == null) return '—'
  return new Date(epoch).toLocaleTimeString([], { hour12: false })
}

function lifespan(row: JfrThreadRow): string {
  if (row.startEpochMs == null || row.endEpochMs == null || row.existedBefore || row.aliveAtEnd) return ''
  const s = Math.round((row.endEpochMs - row.startEpochMs) / 1000)
  return s < 120 ? `${s} 秒` : `${Math.floor(s / 60)} 分 ${s % 60} 秒`
}

function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`
  return `${(bytes / 1024 ** 3).toFixed(2)} GB`
}

function frameParts(frame: string): { pkg: string; name: string; rest: string } {
  const line = frame.match(/:\d+$/)
  const sig = line ? frame.slice(0, line.index) : frame
  const rest = line ? line[0] : ''
  const lastDot = sig.lastIndexOf('.')
  const classDot = lastDot < 0 ? -1 : sig.lastIndexOf('.', lastDot - 1)
  if (classDot < 0) return { pkg: '', name: sig, rest }
  return { pkg: sig.slice(0, classDot + 1), name: sig.slice(classDot + 1), rest }
}

const cpuStats = computed(() => {
  const cpu = detail.value?.cpu ?? []
  if (cpu.length === 0) return null
  const totals = cpu.map(p => (p.user + p.system) * 100)
  const avg = totals.reduce((a, b) => a + b, 0) / totals.length
  return { avg, max: Math.max(...totals), path: sparkline(totals, 360, 56) }
})

const allocStats = computed(() => {
  const alloc = detail.value?.allocation ?? []
  if (alloc.length === 0) return null
  return { total: alloc[alloc.length - 1].allocated, path: sparkline(alloc.map(p => p.allocated), 360, 56) }
})
</script>

<template>
  <div class="threads">
    <p v-if="errorMsg" class="error">{{ errorMsg }}</p>
    <div v-if="loading" class="loading"><span class="spinner"></span>正在整理线程数据…</div>

    <template v-else-if="overview">
      <section class="kpis">
        <div class="kpi">
          <span class="k-label">平台线程</span>
          <span class="k-value">{{ overview.totalThreads.toLocaleString() }}</span>
          <span class="k-foot">录制中观测到的线程</span>
        </div>
        <div class="kpi">
          <span class="k-label">线程池</span>
          <span class="k-value">{{ poolCount }}</span>
          <span class="k-foot">按线程名归类</span>
        </div>
        <div class="kpi created">
          <span class="k-label">录制中新建</span>
          <span class="k-value">+{{ overview.created }}</span>
          <span class="k-foot">jdk.ThreadStart</span>
        </div>
        <div class="kpi ended">
          <span class="k-label">录制中销毁</span>
          <span class="k-value">−{{ overview.ended }}</span>
          <span class="k-foot">jdk.ThreadEnd</span>
        </div>
        <div class="kpi">
          <span class="k-label">净变化</span>
          <span class="k-value" :class="{ pos: overview.created > overview.ended, neg: overview.created < overview.ended }">
            {{ overview.created - overview.ended > 0 ? '+' : '' }}{{ overview.created - overview.ended }}
          </span>
          <span class="k-foot">结束时存活 {{ overview.aliveAtEnd.toLocaleString() }}</span>
        </div>
        <div class="kpi">
          <span class="k-label">JVM 历史峰值</span>
          <span class="k-value">{{ overview.peakActive == null ? '—' : overview.peakActive.toLocaleString() }}</span>
          <span class="k-foot">JVM 启动以来同时存活的最大线程数</span>
        </div>
      </section>

      <section class="card chart-card">
        <header class="card-head">
          <div>
            <h2>
              线程数量变化
              <span class="scope" :class="{ pool: selectedPool }">{{ selectedPool ? selectedPool.name : '全部线程' }}</span>
            </h2>
            <p class="hint">
              上图是每个时间点仍存活的线程数，下图柱子向上是新建、向下是销毁。每格 {{ Math.round(overview.bucketMillis / 1000) }} 秒。
            </p>
          </div>
          <div class="legend">
            <span><i class="sw alive"></i>存活线程</span>
            <span v-if="!selectedPool"><i class="sw jvm dashed"></i>JVM 活跃线程</span>
            <span><i class="sw created"></i>新建</span>
            <span><i class="sw ended"></i>销毁</span>
            <button v-if="selectedPool" class="ghost" type="button" @click="selectPool(null)">看全部线程</button>
          </div>
        </header>
        <JfrThreadTrend
          :series="selectedPool ? selectedPool.series : overview.series"
          :jvm-active="selectedPool ? null : overview.jvmActive"
          :window-start="overview.windowStartEpochMs"
          :bucket-millis="overview.bucketMillis"
          :animate-key="selected ?? '__all__'"
        />
        <div v-if="changedPools.length" class="changed">
          <span class="changed-label">有新建或销毁的池</span>
          <button
            v-for="pool in changedPools.slice(0, 12)"
            :key="pool.name"
            type="button"
            class="chip"
            :class="{ on: selected === pool.name }"
            @click="selectPool(selected === pool.name ? null : pool.name)"
          >
            <span class="chip-name">{{ pool.name }}</span>
            <span v-if="pool.created" class="pos">+{{ pool.created }}</span>
            <span v-if="pool.ended" class="neg">−{{ pool.ended }}</span>
          </button>
        </div>
        <p v-else class="hint quiet">这段录制里没有线程新建或销毁（录制没开 jdk.ThreadStart / jdk.ThreadEnd，或线程池很稳定）。</p>
      </section>

      <section class="card">
        <header class="card-head list-head">
          <h2>线程池 <span class="count">{{ visiblePools.length }}</span></h2>
          <div class="tools">
            <label class="search">
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M8.5 3a5.5 5.5 0 1 0 3.36 9.86l3.64 3.64 1.06-1.06-3.64-3.64A5.5 5.5 0 0 0 8.5 3Zm0 1.5a4 4 0 1 1 0 8 4 4 0 0 1 0-8Z" /></svg>
              <input v-model="query" placeholder="搜索线程池、线程名或线程 ID" />
              <button v-if="query" type="button" class="clear" @click="query = ''">×</button>
            </label>
            <div class="seg">
              <button :class="{ on: sortKey === 'total' }" @click="sortKey = 'total'">按线程数</button>
              <button :class="{ on: sortKey === 'churn' }" @click="sortKey = 'churn'">按变化量</button>
              <button :class="{ on: sortKey === 'alloc' }" title="按录制期间的内存分配量排序（jdk.ThreadAllocationStatistics 首尾差）" @click="sortKey = 'alloc'">按内存分配</button>
              <button :class="{ on: sortKey === 'name' }" @click="sortKey = 'name'">按名称</button>
            </div>
            <label class="toggle">
              <input v-model="onlyChanged" type="checkbox" />
              <span>只看有变化的</span>
            </label>
          </div>
        </header>
        <p v-if="needle" class="hint search-hint">匹配到 {{ matchedThreadCount.toLocaleString() }} 个线程</p>

        <div class="pool-table">
          <div class="pool-cols">
            <span></span>
            <span>线程池</span>
            <span class="num">线程</span>
            <span class="num">新建</span>
            <span class="num">销毁</span>
            <span class="num">结束时存活</span>
            <span class="num">峰值</span>
            <span class="num" title="录制期间分配的内存，不含录制开始前已分配的部分">录制期分配</span>
            <span>存活趋势</span>
          </div>

          <div v-for="{ pool, rows, auto } in visiblePools" :key="pool.name" class="pool" :class="{ open: isOpen(pool.name, auto), selected: selected === pool.name }">
            <div class="pool-row" @click="togglePool(pool, auto)">
              <span class="chev">{{ isOpen(pool.name, auto) ? '▾' : '▸' }}</span>
              <span class="pool-name">
                <b :title="pool.name">{{ pool.name }}</b>
                <em class="type" :class="pool.type">{{ typeLabel(pool) }}</em>
              </span>
              <span class="num strong">{{ pool.total.toLocaleString() }}</span>
              <span class="num"><span v-if="pool.created" class="pill pos">+{{ pool.created }}</span><span v-else class="dash">—</span></span>
              <span class="num"><span v-if="pool.ended" class="pill neg">−{{ pool.ended }}</span><span v-else class="dash">—</span></span>
              <span class="num">{{ pool.aliveAtEnd.toLocaleString() }}</span>
              <span class="num">{{ pool.peakAlive.toLocaleString() }}</span>
              <span class="num alloc" :class="{ sorted: sortKey === 'alloc' }">
                <span>{{ pool.recordedAllocatedBytes ? formatBytes(pool.recordedAllocatedBytes) : '—' }}</span>
                <i v-if="sortKey === 'alloc' && pool.recordedAllocatedBytes" :style="{ width: `${(pool.recordedAllocatedBytes / allocMax) * 100}%` }"></i>
              </span>
              <span class="spark">
                <svg viewBox="0 0 96 22" width="96" height="22" preserveAspectRatio="none">
                  <path :d="sparkline(pool.series.alive)" :class="{ flat: pool.created + pool.ended === 0 }" />
                </svg>
              </span>
            </div>

            <div v-if="isOpen(pool.name, auto)" class="thread-list">
              <div v-if="pool.created + pool.ended > 0" class="pool-trend">
                <div class="pool-trend-head">
                  <b>{{ pool.name }}</b> 线程数变化
                  <span class="pos">+{{ pool.created }}</span><span class="neg">−{{ pool.ended }}</span>
                  <span class="hint">存活 {{ pool.series.alive[0] }} → {{ pool.aliveAtEnd }}，峰值 {{ pool.peakAlive }}</span>
                </div>
                <JfrThreadTrend
                  :series="pool.series"
                  :window-start="overview.windowStartEpochMs"
                  :bucket-millis="overview.bucketMillis"
                  :animate-key="pool.name"
                />
              </div>
              <div class="thread-cols">
                <span>线程</span>
                <span>生命周期</span>
                <span>录制窗口内存活区间</span>
                <span class="num">开始</span>
                <span class="num">结束</span>
                <span class="num">录制期分配</span>
              </div>
              <div
                v-for="row in shownRows(pool.name, rows)"
                :key="row.javaThreadId"
                class="thread-row"
                :class="{ active: detailRow?.javaThreadId === row.javaThreadId }"
                @click="openThread(row)"
              >
                <span class="t-name">
                  <b :title="row.name ?? ''">{{ row.name || '(未命名)' }}</b>
                  <small>#{{ row.javaThreadId }}</small>
                </span>
                <span><i class="life" :class="lifecycle(row).tone">{{ lifecycle(row).label }}</i></span>
                <span class="track"><i :class="lifecycle(row).tone" :style="lifeBar(row)"></i></span>
                <span class="num mono">{{ row.existedBefore ? '录制前' : clock(row.startEpochMs) }}</span>
                <span class="num mono">{{ row.aliveAtEnd ? '仍存活' : clock(row.endEpochMs) }}<small v-if="lifespan(row)" class="span">{{ lifespan(row) }}</small></span>
                <span class="num mono" :title="row.allocatedBytes == null ? '' : `线程启动以来累计 ${formatBytes(row.allocatedBytes)}`">{{ formatBytes(row.recordedAllocatedBytes) }}</span>
              </div>
              <button
                v-if="rows.length > ROW_LIMIT && !showAll[pool.name]"
                type="button"
                class="more"
                @click="showAll[pool.name] = true"
              >显示全部 {{ rows.length.toLocaleString() }} 个线程</button>
            </div>
          </div>
          <p v-if="visiblePools.length === 0" class="empty">没有匹配的线程池或线程。</p>
        </div>
      </section>

      <section v-if="overview.virtualThreads.started > 0" class="card">
        <header class="card-head">
          <h2>虚拟线程</h2>
          <p class="hint">
            启动 {{ overview.virtualThreads.started.toLocaleString() }} · 结束 {{ overview.virtualThreads.ended.toLocaleString() }}
            · 钉住 {{ overview.virtualThreads.pinned.toLocaleString() }}
          </p>
        </header>
        <div v-if="overview.virtualThreads.aggregated" class="vt-groups">
          <div v-for="(group, index) in overview.virtualThreads.groups" :key="index" class="vt-group">
            <b>{{ group.count.toLocaleString() }}</b>
            <span v-if="group.pinned" class="pill neg">钉住 {{ group.pinned }}</span>
            <code>{{ group.stack[0] || '无启动栈' }}</code>
          </div>
        </div>
      </section>
    </template>

    <Transition name="drawer">
      <div v-if="detailRow" class="drawer-mask" :class="{ resizing }" @click.self="closeDetail">
        <aside class="drawer" :style="{ width: `${drawerWidth}px` }">
          <div
            class="drawer-resize"
            title="拖动调整宽度，双击恢复默认"
            @pointerdown.prevent="startResize"
            @dblclick="resetDrawer"
          ><i></i></div>
          <header class="drawer-head">
            <div>
              <h3 :title="detailRow.name ?? ''">{{ detailRow.name || '(未命名)' }}</h3>
              <p>Java 线程 ID {{ detailRow.javaThreadId }}<template v-if="detail?.osThreadId"> · OS 线程 {{ detail.osThreadId }}</template></p>
            </div>
            <button type="button" class="close" @click="closeDetail">×</button>
          </header>
          <div class="drawer-body">
            <div class="life-block">
              <i class="life" :class="lifecycle(detailRow).tone">{{ lifecycle(detailRow).label }}</i>
              <div class="track big"><i :class="lifecycle(detailRow).tone" :style="lifeBar(detailRow)"></i></div>
              <div class="track-labels">
                <span>{{ clock(overview?.windowStartEpochMs) }}</span>
                <span>{{ clock(overview?.windowEndEpochMs) }}</span>
              </div>
            </div>

            <dl class="facts">
              <dt>开始</dt><dd>{{ detailRow.existedBefore ? '录制开始前已存在' : new Date(detailRow.startEpochMs!).toLocaleString() }}</dd>
              <dt>结束</dt><dd>{{ detailRow.aliveAtEnd ? '录制结束时仍存活' : new Date(detailRow.endEpochMs!).toLocaleString() }}</dd>
              <dt v-if="lifespan(detailRow)">存活时长</dt><dd v-if="lifespan(detailRow)">{{ lifespan(detailRow) }}</dd>
              <dt>父线程</dt>
              <dd>{{ detailRow.parentName || '—' }}<small v-if="detail?.parentJavaThreadId"> #{{ detail.parentJavaThreadId }}</small></dd>
              <dt>录制期分配</dt><dd>{{ formatBytes(detailRow.recordedAllocatedBytes) }}</dd>
              <dt>启动以来分配</dt><dd>{{ formatBytes(detailRow.allocatedBytes) }}</dd>
            </dl>

            <div v-if="detailLoading" class="loading small"><span class="spinner"></span>加载详情…</div>
            <template v-else-if="detail">
              <div v-if="cpuStats" class="metric">
                <div class="metric-head"><b>CPU 占用</b><span>平均 {{ cpuStats.avg.toFixed(1) }}% · 最高 {{ cpuStats.max.toFixed(1) }}%</span></div>
                <svg viewBox="0 0 360 56" preserveAspectRatio="none" class="metric-svg"><path :d="cpuStats.path" class="cpu" /></svg>
              </div>
              <div v-if="allocStats" class="metric">
                <div class="metric-head"><b>累计内存分配</b><span>{{ formatBytes(allocStats.total) }}</span></div>
                <svg viewBox="0 0 360 56" preserveAspectRatio="none" class="metric-svg"><path :d="allocStats.path" class="alloc" /></svg>
              </div>
              <div class="stack">
                <div class="metric-head"><b>启动栈</b><span>jdk.ThreadStart</span></div>
                <p v-if="detail.startStack.length === 0" class="hint">
                  {{ detail.existedBefore ? '线程在录制开始前就已创建，没有启动栈。' : '录制没有带上启动栈。' }}
                </p>
                <ol v-else>
                  <li v-for="(frame, index) in detail.startStack" :key="index">
                    <span class="f-pkg">{{ frameParts(frame).pkg }}</span><span class="f-name">{{ frameParts(frame).name }}</span><span class="f-rest">{{ frameParts(frame).rest }}</span>
                  </li>
                </ol>
              </div>
            </template>
          </div>
        </aside>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.threads { display: flex; flex-direction: column; gap: 16px; }
.error { color: var(--ts-danger); margin: 0; }
.loading { display: flex; align-items: center; gap: 10px; color: var(--ts-text-secondary); padding: 40px 0; justify-content: center; }
.loading.small { padding: 16px 0; justify-content: flex-start; }
.spinner { width: 16px; height: 16px; border-radius: 50%; border: 2px solid #dbe3f0; border-top-color: var(--ts-accent); animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.kpis { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px; }
.kpi {
  display: flex; flex-direction: column; gap: 4px;
  padding: 14px 16px;
  background: var(--ts-bg-surface);
  border: var(--ts-border);
  border-radius: 12px;
  box-shadow: var(--ts-shadow-xs);
  position: relative;
  overflow: hidden;
}
.kpi::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: #cbd5e1; }
.kpi.created::before { background: #16a34a; }
.kpi.ended::before { background: #dc2626; }
.k-label { font-size: 12px; color: var(--ts-text-secondary); }
.k-value { font-size: 24px; font-weight: 700; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; color: var(--ts-text-primary); }
.kpi.created .k-value, .k-value.pos { color: #15803d; }
.kpi.ended .k-value, .k-value.neg { color: #b91c1c; }
.k-foot { font-size: 11px; color: var(--ts-text-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.card { background: var(--ts-bg-surface); border: var(--ts-border); border-radius: 14px; box-shadow: var(--ts-shadow-xs); padding: 16px 18px; }
.card-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 10px; }
.card-head h2 { margin: 0; font-size: 15px; font-weight: 650; display: flex; align-items: center; gap: 8px; }
.hint { margin: 4px 0 0; color: var(--ts-text-secondary); font-size: 12.5px; }
.hint.quiet { margin-top: 8px; color: var(--ts-text-muted); }
.scope { font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 999px; background: var(--ts-bg-inset); color: var(--ts-text-secondary); max-width: 360px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.scope.pool { background: var(--ts-accent-light); color: var(--ts-accent); }
.legend { display: flex; align-items: center; gap: 14px; font-size: 12px; color: var(--ts-text-secondary); white-space: nowrap; }
.legend span { display: inline-flex; align-items: center; gap: 6px; }
.sw { width: 10px; height: 10px; border-radius: 3px; display: inline-block; }
.sw.alive { background: #2563eb; }
.sw.jvm { background: transparent; border-top: 2px dashed #7c3aed; height: 0; width: 14px; border-radius: 0; }
.sw.created { background: #16a34a; }
.sw.ended { background: #dc2626; }
.ghost { border: var(--ts-border); background: var(--ts-bg-surface); color: var(--ts-text-primary); border-radius: 8px; padding: 4px 10px; font: inherit; font-size: 12px; cursor: pointer; }
.ghost:hover { border-color: var(--ts-accent); color: var(--ts-accent); }

.changed { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 10px; padding-top: 12px; border-top: 1px dashed var(--ts-border-color); }
.changed-label { font-size: 12px; color: var(--ts-text-muted); margin-right: 4px; }
.chip { display: inline-flex; align-items: center; gap: 6px; border: var(--ts-border); background: var(--ts-bg-surface); border-radius: 999px; padding: 4px 10px; font: inherit; font-size: 12px; cursor: pointer; transition: all var(--ts-transition); }
.chip:hover { border-color: #93b4f5; background: #f7faff; }
.chip.on { border-color: var(--ts-accent); background: var(--ts-accent-light); box-shadow: var(--ts-shadow-ring); }
.chip-name { max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ts-text-primary); font-weight: 550; }
.pos { color: #15803d; font-weight: 650; font-variant-numeric: tabular-nums; }
.neg { color: #b91c1c; font-weight: 650; font-variant-numeric: tabular-nums; }

.list-head { align-items: center; }
.count { font-size: 12px; font-weight: 600; color: var(--ts-text-secondary); background: var(--ts-bg-inset); border-radius: 999px; padding: 1px 8px; }
.tools { display: flex; align-items: center; gap: 10px; }
.search { position: relative; display: flex; align-items: center; }
.search svg { position: absolute; left: 10px; width: 15px; height: 15px; fill: var(--ts-text-muted); }
.search input { width: 280px; border: var(--ts-border); border-radius: 9px; padding: 7px 28px 7px 32px; font: inherit; font-size: 13px; background: var(--ts-bg-surface); color: var(--ts-text-primary); outline: none; transition: border-color var(--ts-transition), box-shadow var(--ts-transition); }
.search input:focus { border-color: var(--ts-accent); box-shadow: var(--ts-shadow-ring); }
.clear { position: absolute; right: 6px; border: 0; background: transparent; color: var(--ts-text-muted); font-size: 16px; cursor: pointer; line-height: 1; padding: 2px 4px; }
.clear:hover { color: var(--ts-text-primary); }
.seg { display: inline-flex; background: var(--ts-bg-inset); border-radius: 9px; padding: 3px; }
.seg button { border: 0; background: transparent; padding: 5px 10px; border-radius: 7px; font: inherit; font-size: 12.5px; color: var(--ts-text-secondary); cursor: pointer; }
.seg button.on { background: var(--ts-bg-surface); color: var(--ts-text-primary); font-weight: 600; box-shadow: var(--ts-shadow-sm); }
.toggle { display: inline-flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--ts-text-secondary); cursor: pointer; white-space: nowrap; }
.toggle input { accent-color: var(--ts-accent); }
.search-hint { margin: -4px 0 8px; }

.pool-table { border: var(--ts-border); border-radius: 10px; overflow: hidden; }
.pool-cols, .pool-row {
  display: grid;
  grid-template-columns: 22px minmax(220px, 1fr) 64px 64px 64px 88px 56px 104px 112px;
  align-items: center;
  gap: 8px;
  padding: 0 14px;
}
.pool-cols { height: 34px; background: var(--ts-bg-inset); color: var(--ts-text-secondary); font-size: 12px; font-weight: 600; border-bottom: var(--ts-border); }
.pool + .pool { border-top: var(--ts-border); }
.pool-row { min-height: 44px; cursor: pointer; transition: background var(--ts-transition); }
.pool-row:hover { background: #f7f9fc; }
.pool.selected > .pool-row { background: var(--ts-accent-light); box-shadow: inset 3px 0 0 var(--ts-accent); }
.chev { color: var(--ts-text-muted); font-size: 12px; }
.pool-name { display: flex; align-items: center; gap: 8px; min-width: 0; }
.pool-name b { font-family: var(--ts-font-display); font-size: 13px; font-weight: 600; color: var(--ts-text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.type { font-style: normal; font-size: 11px; padding: 1px 7px; border-radius: 999px; background: #eef2ff; color: #4338ca; white-space: nowrap; flex: none; }
.type.Custom { background: #f1f5f9; color: #475569; }
.type.Standalone { background: #f8fafc; color: #64748b; border: 1px dashed #cbd5e1; }
.type.JVM-Internal { background: #fef3c7; color: #92400e; }
.num { text-align: right; font-variant-numeric: tabular-nums; font-size: 13px; color: var(--ts-text-primary); }
.num.strong { font-weight: 650; }
.dash { color: var(--ts-text-faint); }
.num.alloc { display: flex; flex-direction: column; align-items: flex-end; gap: 3px; color: var(--ts-text-secondary); }
.num.alloc.sorted { color: var(--ts-text-primary); font-weight: 600; }
.num.alloc i { display: block; height: 3px; border-radius: 3px; background: #0891b2; min-width: 2px; align-self: flex-end; }
.pill { display: inline-block; padding: 1px 7px; border-radius: 6px; font-size: 12px; }
.pill.pos { background: #dcfce7; }
.pill.neg { background: #fee2e2; }
.spark svg { display: block; }
.spark path { fill: none; stroke: #2563eb; stroke-width: 1.6; }
.spark path.flat { stroke: #cbd5e1; }

.thread-list { background: #fbfcfe; border-top: var(--ts-border); padding: 4px 0 8px; animation: open 180ms ease-out; }
@keyframes open { from { opacity: 0; transform: translateY(-4px); } to { opacity: 1; transform: none; } }
.pool-trend { margin: 8px 14px 6px 44px; padding: 12px 14px 6px; background: var(--ts-bg-surface); border: var(--ts-border); border-radius: 10px; }
.pool-trend-head { display: flex; align-items: baseline; gap: 8px; font-size: 12.5px; color: var(--ts-text-secondary); margin-bottom: 4px; }
.pool-trend-head b { font-family: var(--ts-font-display); color: var(--ts-text-primary); font-weight: 600; }
.pool-trend-head .hint { margin: 0 0 0 auto; }
.thread-cols, .thread-row {
  display: grid;
  grid-template-columns: minmax(220px, 1.4fr) 100px minmax(160px, 1fr) 80px 110px 84px;
  align-items: center;
  gap: 10px;
  padding: 0 14px 0 44px;
}
.thread-cols { height: 30px; font-size: 11.5px; color: var(--ts-text-muted); font-weight: 600; }
.thread-row { min-height: 34px; cursor: pointer; border-radius: 6px; margin: 0 6px; transition: background var(--ts-transition); }
.thread-row:hover { background: #eef3fb; }
.thread-row.active { background: var(--ts-accent-light); }
.t-name { display: flex; align-items: baseline; gap: 6px; min-width: 0; }
.t-name b { font-family: var(--ts-font-display); font-size: 12.5px; font-weight: 500; color: var(--ts-text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.t-name small { color: var(--ts-text-muted); font-size: 11px; flex: none; }
.life { font-style: normal; font-size: 11px; font-weight: 600; padding: 2px 7px; border-radius: 6px; white-space: nowrap; }
.life.steady { background: #f1f5f9; color: #64748b; }
.life.created { background: #dcfce7; color: #15803d; }
.life.ended { background: #fee2e2; color: #b91c1c; }
.life.both { background: #f3e8ff; color: #7e22ce; }
.track { position: relative; height: 8px; border-radius: 999px; background: #edf1f6; overflow: hidden; }
.track i { position: absolute; top: 0; bottom: 0; border-radius: 999px; }
.track i.steady { background: #94a3b8; }
.track i.created { background: #22c55e; }
.track i.ended { background: #ef4444; }
.track i.both { background: #a855f7; }
.mono { font-family: var(--ts-font-display); font-size: 12px; }
.span { display: block; font-size: 10.5px; color: var(--ts-text-muted); }
.more { display: block; margin: 8px auto 0; border: var(--ts-border); background: var(--ts-bg-surface); border-radius: 8px; padding: 6px 14px; font: inherit; font-size: 12.5px; color: var(--ts-accent); cursor: pointer; }
.empty { text-align: center; color: var(--ts-text-muted); padding: 28px 0; margin: 0; }

.vt-groups { display: flex; flex-direction: column; gap: 6px; }
.vt-group { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.vt-group code { font-family: var(--ts-font-display); font-size: 12px; color: var(--ts-text-secondary); }

.drawer-mask { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.18); z-index: 50; display: flex; justify-content: flex-end; }
.drawer { position: relative; max-width: 95vw; height: 100%; background: var(--ts-bg-surface); box-shadow: -12px 0 32px rgba(15, 23, 42, 0.12); display: flex; flex-direction: column; }
.drawer-mask.resizing { cursor: col-resize; user-select: none; }
.drawer-mask.resizing .drawer { transition: none; }
.drawer-resize { position: absolute; left: -5px; top: 0; bottom: 0; width: 10px; cursor: col-resize; z-index: 2; touch-action: none; display: flex; align-items: center; justify-content: center; }
.drawer-resize::before { content: ''; position: absolute; left: 4px; top: 0; bottom: 0; width: 2px; background: transparent; transition: background 150ms ease; }
.drawer-resize i { position: relative; width: 6px; height: 40px; border-radius: 3px; background: #cbd5e1; box-shadow: 0 0 0 2px var(--ts-bg-surface); transition: background 150ms ease, height 150ms ease; }
.drawer-resize:hover::before, .drawer-mask.resizing .drawer-resize::before { background: var(--ts-accent); }
.drawer-resize:hover i, .drawer-mask.resizing .drawer-resize i { background: var(--ts-accent); height: 56px; }
.drawer-enter-active, .drawer-leave-active { transition: background 220ms ease; }
.drawer-enter-active .drawer, .drawer-leave-active .drawer { transition: transform 240ms cubic-bezier(.2,.8,.2,1); }
.drawer-enter-from, .drawer-leave-to { background: rgba(15, 23, 42, 0); }
.drawer-enter-from .drawer, .drawer-leave-to .drawer { transform: translateX(100%); }
.drawer-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; padding: 18px 20px 14px; border-bottom: var(--ts-border); }
.drawer-head h3 { margin: 0; font-family: var(--ts-font-display); font-size: 15px; font-weight: 650; word-break: break-all; }
.drawer-head p { margin: 4px 0 0; color: var(--ts-text-secondary); font-size: 12.5px; }
.close { border: 0; background: var(--ts-bg-inset); width: 28px; height: 28px; border-radius: 8px; font-size: 18px; color: var(--ts-text-secondary); cursor: pointer; flex: none; }
.close:hover { color: var(--ts-text-primary); background: var(--ts-bg-active); }
.drawer-body { flex: 1; overflow-y: auto; padding: 16px 20px 32px; display: flex; flex-direction: column; gap: 18px; }
.life-block { display: flex; flex-direction: column; gap: 8px; align-items: flex-start; }
.track.big { width: 100%; height: 12px; }
.track-labels { width: 100%; display: flex; justify-content: space-between; font-size: 11px; color: var(--ts-text-muted); font-variant-numeric: tabular-nums; }
.facts { display: grid; grid-template-columns: 84px 1fr; gap: 8px 12px; margin: 0; font-size: 13px; }
.facts dt { color: var(--ts-text-secondary); }
.facts dd { margin: 0; color: var(--ts-text-primary); word-break: break-all; }
.facts small { color: var(--ts-text-muted); }
.metric { border: var(--ts-border); border-radius: 10px; padding: 10px 12px; }
.metric-head { display: flex; justify-content: space-between; align-items: baseline; font-size: 12.5px; margin-bottom: 6px; }
.metric-head span { color: var(--ts-text-secondary); font-variant-numeric: tabular-nums; }
.metric-svg { width: 100%; height: 56px; display: block; }
.metric-svg path { fill: none; stroke-width: 1.6; vector-effect: non-scaling-stroke; }
.metric-svg .cpu { stroke: #ea580c; }
.metric-svg .alloc { stroke: #0891b2; }
.stack ol { margin: 0; padding: 0 0 0 26px; font-family: var(--ts-font-display); font-size: 12px; line-height: 1.75; }
.stack li { word-break: break-all; }
.stack li::marker { color: var(--ts-text-faint); }
.f-pkg { color: var(--ts-text-secondary); }
.f-name { color: var(--ts-accent); font-weight: 600; }
.f-rest { color: var(--ts-text-muted); }

.card-head { flex-wrap: wrap; }
.tools { flex-wrap: wrap; }
@media (max-width: 1280px) {
  .kpis { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
</style>
