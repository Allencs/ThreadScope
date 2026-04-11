<script setup lang="ts">
/**
 * DashboardView — Overview 概览页面。
 *
 * 布局：
 * ┌─────────────────────────────────────────────────────┐
 * │ Row 1: 分析概况                                      │
 * │   线程状态环形图 + 图例  │  Daemon 环形图  │ 指标卡    │
 * ├─────────────────────────────────────────────────────┤
 * │ Row 2: TOP 10 列表 (多 Tab)                          │
 * │   线程 | 线程池 | 线程栈 | 方法 | 锁                   │
 * ├─────────────────────────────────────────────────────┤
 * │ Row 3: Blocked Thread Risks (仅当存在时显示)           │
 * └─────────────────────────────────────────────────────┘
 */
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAnalysisStore } from '@/stores/analysisStore'
import { STATE_COLORS, STATE_LABELS, type ThreadState, type ThreadInfo, type ThreadPoolInfo, type MethodHotspot, type LockInfo } from '@/types'
import * as api from '@/api/threadscope'

const store = useAnalysisStore()
const route = useRoute()
const router = useRouter()

// ── TOP 10 data ──
const topThreads = ref<ThreadInfo[]>([])
const topPools = ref<ThreadPoolInfo[]>([])
const topMethods = ref<MethodHotspot[]>([])
const topLocks = ref<LockInfo[]>([])
const activeTab = ref<'threads' | 'pools' | 'methods' | 'locks'>('threads')
const topLoading = ref(false)

onMounted(async () => {
  if (!store.overview) {
    await store.loadOverview()
  }
  loadTopData()
})

async function loadTopData() {
  if (!store.analysisId) return
  topLoading.value = true
  try {
    const [threadsRes, poolsRes, methodsRes, locksRes] = await Promise.all([
      api.fetchThreads(store.analysisId, { sort: 'stackDepth', page: 1, size: 10 }),
      api.fetchThreadPools(store.analysisId),
      api.fetchMethodHotspots(store.analysisId, 10),
      api.fetchLocks(store.analysisId),
    ])
    topThreads.value = threadsRes.threads
    topPools.value = poolsRes.pools.sort((a, b) => b.totalThreads - a.totalThreads).slice(0, 10)
    topMethods.value = methodsRes.hotspots
    topLocks.value = locksRes.lockInfos.sort((a, b) => b.waitingThreadNames.length - a.waitingThreadNames.length).slice(0, 10)
  } finally {
    topLoading.value = false
  }
}

// ── Pie chart data ──
const stateEntries = computed(() => {
  if (!store.overview?.stateDistribution) return []
  const dist = store.overview.stateDistribution
  return Object.entries(dist)
    .map(([state, count]) => ({
      state: state as ThreadState,
      count: count as number,
      color: STATE_COLORS[state as ThreadState] || '#9ca3af',
      label: STATE_LABELS[state as ThreadState] || state,
    }))
    .sort((a, b) => b.count - a.count)
})

const totalThreads = computed(() => store.overview?.totalThreads ?? 0)

// Daemon pie entries
const daemonEntries = computed(() => {
  if (!store.overview) return []
  return [
    { label: 'Daemon', count: store.overview.daemonCount, color: '#6366f1' },
    { label: 'Non-Daemon', count: store.overview.nonDaemonCount, color: '#22c55e' },
  ]
})

// ── Blocked risks — 仅 BLOCK_STORM 和 DEADLOCK ──
const blockedRisks = computed(() => {
  const risks = store.overview?.healthReport?.risks ?? []
  return risks.filter(r => r.category === 'BLOCK_STORM' || r.category === 'DEADLOCK')
})

const healthClass = computed(() => {
  const level = store.overview?.healthReport?.overallLevel
  return { 'CRITICAL': 'health-critical', 'WARNING': 'health-warning', 'HEALTHY': 'health-healthy' }[level ?? 'HEALTHY']
})

// ── Pie chart SVG helpers ──
function pieArcs(entries: { count: number; color: string }[], total: number, radius: number) {
  if (total === 0) return []
  const circumference = 2 * Math.PI * radius
  let accumulated = 0
  return entries.map(e => {
    const pct = e.count / total
    const dash = pct * circumference
    const offset = -accumulated * circumference + circumference * 0.25 // start from top
    accumulated += pct
    return { ...e, dashArray: `${dash} ${circumference}`, dashOffset: offset }
  })
}

// ── Navigation ──
function extractLockAddress(desc: string): string | null {
  const m = desc.match(/0x[0-9a-fA-F]+/)
  return m ? m[0] : null
}

function navigateToLock(lockAddr: string) {
  store.highlightLockAddress = lockAddr
  router.push({ name: 'locks', params: { analysisId: route.params.analysisId } })
}

function navigateToThread(threadName: string) {
  store.openThreadDetailTab(threadName)
  router.push({ name: 'threads', params: { analysisId: route.params.analysisId } })
}

function navigateToPool(_poolName: string) {
  router.push({ name: 'pools', params: { analysisId: route.params.analysisId } })
}

function navigateToMethod(className: string, methodName: string) {
  // 使用 "类短名.方法名" 作为搜索关键词 — 后端 matchesSearch 会在 stackTrace 中匹配
  const shortClass = className.split('.').pop() ?? className
  store.searchQuery = shortClass + '.' + methodName
  router.push({ name: 'threads', params: { analysisId: route.params.analysisId } })
}

const tabs = [
  { key: 'threads', label: 'Threads' },
  { key: 'pools', label: 'Thread Pools' },
  { key: 'methods', label: 'Methods' },
  { key: 'locks', label: 'Locks' },
]
</script>

<template>
  <div class="dashboard">

    <!-- ═══════════════════════════════════════
         Row 1: 分析概况
         ═══════════════════════════════════════ -->
    <div class="overview-panel">
      <h3 class="section-title">Analysis Overview</h3>
      <div class="overview-grid">

        <!-- 线程状态环形图 -->
        <div class="chart-block chart-block--main">
          <div class="chart-label">Thread State Distribution</div>
          <div class="chart-row">
            <div class="donut-container donut-container--large">
              <svg viewBox="0 0 200 200">
                <circle cx="100" cy="100" r="70" fill="none" stroke="#f0f2f5" stroke-width="24"/>
                <circle
                  v-for="(arc, i) in pieArcs(stateEntries, totalThreads, 70)"
                  :key="i"
                  cx="100" cy="100" r="70"
                  fill="none"
                  :stroke="arc.color"
                  stroke-width="24"
                  :stroke-dasharray="arc.dashArray"
                  :stroke-dashoffset="arc.dashOffset"
                  stroke-linecap="butt"
                  style="transition: stroke-dasharray 0.6s ease"
                />
              </svg>
              <div class="donut-center">
                <span class="donut-center__value mono">{{ totalThreads }}</span>
                <span class="donut-center__label">threads</span>
              </div>
            </div>
            <ul class="legend">
              <li v-for="e in stateEntries" :key="e.state" class="legend-item" :class="{ 'legend-item--alert': e.state === 'BLOCKED' && e.count > 0 }">
                <span class="legend-dot" :style="{ background: e.color }"></span>
                <span class="legend-name">{{ e.label }}</span>
                <span class="legend-count mono">{{ e.count }}</span>
                <span class="legend-pct mono">{{ (e.count / totalThreads * 100).toFixed(1) }}%</span>
              </li>
            </ul>
          </div>
        </div>

        <!-- Daemon 环形图 -->
        <div class="chart-block">
          <div class="chart-label">Daemon Threads</div>
          <div class="chart-row">
            <div class="donut-container donut-container--medium">
              <svg viewBox="0 0 200 200">
                <circle cx="100" cy="100" r="70" fill="none" stroke="#f0f2f5" stroke-width="24"/>
                <circle
                  v-for="(arc, i) in pieArcs(daemonEntries, totalThreads, 70)"
                  :key="i"
                  cx="100" cy="100" r="70"
                  fill="none"
                  :stroke="arc.color"
                  stroke-width="24"
                  :stroke-dasharray="arc.dashArray"
                  :stroke-dashoffset="arc.dashOffset"
                  stroke-linecap="butt"
                />
              </svg>
              <div class="donut-center">
                <span class="donut-center__value donut-center__value--sm mono">{{ store.overview?.daemonCount ?? 0 }}</span>
                <span class="donut-center__label">daemon</span>
              </div>
            </div>
            <ul class="legend legend--compact">
              <li v-for="e in daemonEntries" :key="e.label" class="legend-item">
                <span class="legend-dot" :style="{ background: e.color }"></span>
                <span class="legend-name">{{ e.label }}</span>
                <span class="legend-count mono">{{ e.count }}</span>
                <span class="legend-pct mono">{{ totalThreads > 0 ? (e.count / totalThreads * 100).toFixed(1) : '0' }}%</span>
              </li>
            </ul>
          </div>
        </div>

        <!-- 关键指标卡片 -->
        <div class="metrics-column">
          <div class="metric-card" :class="{ 'metric-card--alert': (store.overview?.deadlockCount ?? 0) > 0 }">
            <span class="metric-value mono">{{ store.overview?.deadlockCount ?? 0 }}</span>
            <span class="metric-label">Deadlocks</span>
          </div>
          <div class="metric-card">
            <span class="metric-value mono">{{ store.overview?.threadPoolCount ?? 0 }}</span>
            <span class="metric-label">Thread Pools</span>
          </div>
          <div class="metric-card">
            <span class="metric-value mono">{{ store.overview?.lockContentionCount ?? 0 }}</span>
            <span class="metric-label">Lock Contentions</span>
          </div>
          <div class="metric-card metric-card--subtle">
            <span class="metric-value mono">{{ store.overview?.parseTimeMs ?? 0 }}<small>ms</small></span>
            <span class="metric-label">Parse Time</span>
          </div>
        </div>

      </div>
    </div>

    <!-- ═══════════════════════════════════════
         Row 2: TOP 10 列表 (中间位置)
         ═══════════════════════════════════════ -->
    <div class="top-panel">
      <div class="top-header">
        <h3 class="section-title section-title--inline">TOP 10</h3>
        <div class="tab-bar">
          <button
            v-for="tab in tabs"
            :key="tab.key"
            class="tab-btn"
            :class="{ 'tab-btn--active': activeTab === tab.key }"
            @click="activeTab = tab.key as typeof activeTab"
          >{{ tab.label }}</button>
        </div>
      </div>

      <!-- ── Tab: Threads (by stack depth) ── -->
      <div v-if="activeTab === 'threads'" class="top-table">
        <div class="table-header">
          <span class="col col--name">Thread Name</span>
          <span class="col col--pool">Pool</span>
          <span class="col col--num">Stack Depth</span>
          <span class="col col--nid">NID</span>
          <span class="col col--daemon">Daemon</span>
          <span class="col col--state">State</span>
        </div>
        <div
          v-for="(t, i) in topThreads"
          :key="t.name"
          class="table-row table-row--clickable"
          @click="navigateToThread(t.name)"
        >
          <span class="col col--name">
            <span class="row-rank mono">{{ i + 1 }}</span>
            <span class="mono">{{ t.name }}</span>
          </span>
          <span class="col col--pool mono text-muted">{{ detectPool(t.name) }}</span>
          <span class="col col--num mono">{{ t.stackTrace.length }}</span>
          <span class="col col--nid mono text-muted">{{ t.nid }}</span>
          <span class="col col--daemon">
            <span v-if="t.daemon" class="check-icon">✓</span>
            <span v-else class="text-muted">—</span>
          </span>
          <span class="col col--state">
            <span class="state-dot" :style="{ background: STATE_COLORS[t.state] || '#9ca3af' }"></span>
            {{ STATE_LABELS[t.state] || t.state }}
          </span>
        </div>
        <div v-if="topThreads.length === 0" class="table-empty">No thread data</div>
      </div>

      <!-- ── Tab: Pools ── -->
      <div v-if="activeTab === 'pools'" class="top-table">
        <div class="table-header">
          <span class="col col--name">Pool Name</span>
          <span class="col col--type">Type</span>
          <span class="col col--num">Threads</span>
          <span class="col col--dist">State Distribution</span>
        </div>
        <div
          v-for="(p, i) in topPools"
          :key="p.poolName"
          class="table-row table-row--clickable"
          @click="navigateToPool(p.poolName)"
        >
          <span class="col col--name">
            <span class="row-rank mono">{{ i + 1 }}</span>
            <span class="mono">{{ p.poolName }}</span>
          </span>
          <span class="col col--type mono text-muted">{{ p.poolType }}</span>
          <span class="col col--num mono">{{ p.totalThreads }}</span>
          <span class="col col--dist">
            <div class="mini-bar">
              <div
                v-for="(count, state) in p.stateDistribution"
                :key="state"
                class="mini-bar__seg"
                :style="{
                  width: (Number(count) / p.totalThreads * 100) + '%',
                  background: STATE_COLORS[state as ThreadState] || '#9ca3af'
                }"
                :title="state + ': ' + count"
              ></div>
            </div>
          </span>
        </div>
        <div v-if="topPools.length === 0" class="table-empty">No pool data</div>
      </div>

      <!-- ── Tab: Methods ── -->
      <div v-if="activeTab === 'methods'" class="top-table">
        <div class="table-header">
          <span class="col col--name">Method</span>
          <span class="col col--num">Occurrences</span>
          <span class="col col--pct">Percentage</span>
          <span class="col col--threads">Sample Threads</span>
        </div>
        <div
          v-for="(m, i) in topMethods"
          :key="m.className + '.' + m.methodName"
          class="table-row table-row--clickable"
          @click="navigateToMethod(m.className, m.methodName)"
        >
          <span class="col col--name">
            <span class="row-rank mono">{{ i + 1 }}</span>
            <span class="mono text-sm">{{ m.className.split('.').pop() }}.{{ m.methodName }}</span>
          </span>
          <span class="col col--num mono">{{ m.occurrences }}</span>
          <span class="col col--pct mono">{{ m.percentage.toFixed(1) }}%</span>
          <span class="col col--threads">
            <span class="thread-pill mono" v-for="t in m.sampleThreads.slice(0, 2)" :key="t">{{ t }}</span>
            <span v-if="m.sampleThreads.length > 2" class="thread-pill thread-pill--more">+{{ m.sampleThreads.length - 2 }}</span>
          </span>
        </div>
        <div v-if="topMethods.length === 0" class="table-empty">No method data</div>
      </div>

      <!-- ── Tab: Locks ── -->
      <div v-if="activeTab === 'locks'" class="top-table">
        <div class="table-header">
          <span class="col col--name">Lock Address</span>
          <span class="col col--class">Class</span>
          <span class="col col--holder">Holder</span>
          <span class="col col--num">Waiters</span>
        </div>
        <div
          v-for="(l, i) in topLocks"
          :key="l.lockAddress"
          class="table-row table-row--clickable"
          @click="navigateToLock(l.lockAddress)"
        >
          <span class="col col--name">
            <span class="row-rank mono">{{ i + 1 }}</span>
            <span class="mono">{{ l.lockAddress }}</span>
          </span>
          <span class="col col--class mono text-sm text-muted">{{ l.lockClassName.split('.').pop() }}</span>
          <span class="col col--holder mono text-sm">{{ l.holderThreadName ?? '(none)' }}</span>
          <span class="col col--num mono" :class="{ 'text-danger': l.waitingThreadNames.length >= 10 }">
            {{ l.waitingThreadNames.length }}
          </span>
        </div>
        <div v-if="topLocks.length === 0" class="table-empty">No lock data</div>
      </div>
    </div>

    <!-- ═══════════════════════════════════════
         Row 3: Blocked Thread Risks (页面最下方，仅存在时显示)
         ═══════════════════════════════════════ -->
    <div v-if="blockedRisks.length > 0" class="blocked-panel" :class="healthClass">
      <div class="blocked-header">
        <h3 class="section-title section-title--inline">Blocked Thread Risks</h3>
        <span class="health-badge" :class="healthClass">
          {{ store.overview?.healthReport?.overallLevel ?? 'HEALTHY' }}
        </span>
      </div>
      <div class="risk-grid">
        <div
          v-for="(risk, idx) in blockedRisks"
          :key="idx"
          class="risk-card"
          :class="'risk-card--' + risk.level.toLowerCase()"
        >
          <div class="risk-card__header">
            <span class="risk-card__title">{{ risk.title }}</span>
            <span class="risk-card__badge mono">{{ risk.category }}</span>
          </div>
          <p class="risk-card__desc">{{ risk.description }}</p>
          <a
            v-if="extractLockAddress(risk.description)"
            class="risk-card__lock-link mono"
            href="#"
            @click.prevent="navigateToLock(extractLockAddress(risk.description)!)"
          >
            {{ extractLockAddress(risk.description) }}
            <span class="risk-card__arrow">→ View in Locks</span>
          </a>
          <div v-if="risk.affectedThreads?.length" class="risk-card__threads">
            <span
              class="thread-pill thread-pill--clickable mono"
              v-for="t in risk.affectedThreads.slice(0, 3)"
              :key="t"
              @click="navigateToThread(t)"
              :title="'View ' + t + ' in Threads'"
            >{{ t }}<span class="thread-pill__arrow">→</span></span>
            <span v-if="risk.affectedThreads.length > 3" class="thread-pill thread-pill--more">+{{ risk.affectedThreads.length - 3 }} more</span>
          </div>
        </div>
      </div>
    </div>

  </div>
</template>

<script lang="ts">
/** Detect pool name from thread name (simple heuristic). */
function detectPool(threadName: string): string {
  const m = threadName.match(/^(.+?)-?\d+$/)
  if (m) {
    const prefix = m[1].replace(/-$/, '')
    if (prefix.length > 3 && prefix !== threadName) return prefix
  }
  return '—'
}
</script>

<style scoped>
/* ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   DashboardView — Overview v4
   Layout: Overview → TOP 10 → Blocked Risks
   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ */

.dashboard {
  display: flex;
  flex-direction: column;
  gap: var(--ts-space-lg);
}

/* ── Section title ── */
.section-title {
  font-size: var(--ts-font-size-md);
  font-weight: 600;
  color: var(--ts-text-primary);
  margin: 0 0 var(--ts-space-md) 0;
}

.section-title--inline {
  margin-bottom: 0;
}

/* ══════════════════════════════════════
   Row 1: Analysis Overview
   ══════════════════════════════════════ */
.overview-panel {
  background: #ffffff;
  border: 1px solid var(--ts-border-color);
  border-radius: var(--ts-radius-lg);
  padding: var(--ts-space-lg);
  box-shadow: var(--ts-shadow-sm);
}

.overview-grid {
  display: grid;
  grid-template-columns: 1.4fr 1fr auto;
  gap: var(--ts-space-xl);
  align-items: start;
}

/* ── Chart block ── */
.chart-block {
  display: flex;
  flex-direction: column;
  gap: var(--ts-space-sm);
}

.chart-label {
  font-size: var(--ts-font-size-xs);
  font-weight: 600;
  color: var(--ts-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.chart-row {
  display: flex;
  align-items: center;
  gap: var(--ts-space-lg);
}

/* ── Donut ── */
.donut-container {
  position: relative;
  flex-shrink: 0;
}

.donut-container--large {
  width: 160px;
  height: 160px;
}

.donut-container--medium {
  width: 120px;
  height: 120px;
}

.donut-container svg {
  width: 100%;
  height: 100%;
}

.donut-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}

.donut-center__value {
  font-size: 22px;
  font-weight: 700;
  color: var(--ts-text-primary);
  line-height: 1;
}

.donut-center__value--sm {
  font-size: 17px;
}

.donut-center__label {
  font-size: 10px;
  color: var(--ts-text-secondary);
  margin-top: 2px;
}

/* ── Legend ── */
.legend {
  flex: 1;
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.legend--compact {
  gap: 8px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: var(--ts-space-sm);
  font-size: var(--ts-font-size-sm);
  padding: 3px 6px;
  border-radius: var(--ts-radius-sm);
  transition: background var(--ts-transition);
}

.legend-item--alert {
  background: #fef2f2;
  border: 1px solid #fecaca;
}

.legend-dot {
  width: 8px;
  height: 8px;
  border-radius: var(--ts-radius-full);
  flex-shrink: 0;
}

.legend-name {
  flex: 1;
  color: var(--ts-text-secondary);
}

.legend-count {
  font-weight: 600;
  color: var(--ts-text-primary);
  min-width: 32px;
  text-align: right;
}

.legend-pct {
  color: var(--ts-text-secondary);
  min-width: 44px;
  text-align: right;
  font-size: var(--ts-font-size-xs);
}

/* ── Metrics column ── */
.metrics-column {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--ts-space-sm);
  min-width: 200px;
}

.metric-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--ts-space-md) var(--ts-space-sm);
  background: var(--ts-bg-elevated);
  border: 1px solid var(--ts-border-color);
  border-radius: var(--ts-radius-md);
  text-align: center;
  transition: box-shadow var(--ts-transition);
}

.metric-card:hover {
  box-shadow: var(--ts-shadow-sm);
}

.metric-card--alert {
  border-color: #fecaca;
  background: #fef2f2;
}

.metric-card--alert .metric-value {
  color: var(--ts-danger);
}

.metric-card--subtle {
  background: transparent;
  border-style: dashed;
}

.metric-value {
  font-size: var(--ts-font-size-xl);
  font-weight: 700;
  color: var(--ts-text-primary);
  line-height: 1.2;
}

.metric-value small {
  font-size: 11px;
  font-weight: 500;
  color: var(--ts-text-secondary);
}

.metric-label {
  font-size: 10px;
  color: var(--ts-text-secondary);
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  margin-top: 2px;
}

/* ══════════════════════════════════════
   Row 2: TOP 10
   ══════════════════════════════════════ */
.top-panel {
  background: #ffffff;
  border: 1px solid var(--ts-border-color);
  border-radius: var(--ts-radius-lg);
  padding: var(--ts-space-lg);
  box-shadow: var(--ts-shadow-sm);
}

.top-header {
  display: flex;
  align-items: center;
  gap: var(--ts-space-lg);
  margin-bottom: var(--ts-space-md);
}

/* ── Tab bar ── */
.tab-bar {
  display: flex;
  gap: 2px;
  background: var(--ts-bg-elevated);
  border-radius: var(--ts-radius-md);
  padding: 2px;
}

.tab-btn {
  padding: 6px 14px;
  font-size: var(--ts-font-size-sm);
  font-weight: 500;
  color: var(--ts-text-secondary);
  border: none;
  border-radius: var(--ts-radius-sm);
  background: transparent;
  cursor: pointer;
  transition: all var(--ts-transition);
  white-space: nowrap;
}

.tab-btn:hover {
  color: var(--ts-text-primary);
  background: rgba(0, 0, 0, 0.03);
}

.tab-btn--active {
  color: var(--ts-text-primary);
  background: #ffffff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
  font-weight: 600;
}

/* ── Table ── */
.top-table {
  border: 1px solid var(--ts-border-color);
  border-radius: var(--ts-radius-md);
  overflow: hidden;
}

.table-header {
  display: flex;
  align-items: center;
  padding: 8px 14px;
  background: var(--ts-bg-elevated);
  border-bottom: 1px solid var(--ts-border-color);
  font-size: 10px;
  font-weight: 600;
  color: var(--ts-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.4px;
  gap: var(--ts-space-sm);
}

.table-row {
  display: flex;
  align-items: center;
  padding: 10px 14px;
  border-bottom: 1px solid var(--ts-border-color);
  font-size: var(--ts-font-size-sm);
  gap: var(--ts-space-sm);
  transition: background var(--ts-transition);
}

.table-row:last-child {
  border-bottom: none;
}

.table-row:hover {
  background: var(--ts-bg-hover);
}

.table-row--clickable {
  cursor: pointer;
}

.table-row--clickable:hover {
  background: #f0f4ff;
}

.table-empty {
  padding: var(--ts-space-xl);
  text-align: center;
  color: var(--ts-text-muted);
  font-size: var(--ts-font-size-sm);
}

/* ── Column sizes ── */
.col { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.col--name { flex: 2; display: flex; align-items: center; gap: 6px; min-width: 0; }
.col--pool { flex: 0.8; }
.col--type { flex: 0.6; }
.col--class { flex: 1; }
.col--holder { flex: 1; }
.col--num { flex: 0.4; text-align: right; min-width: 60px; }
.col--nid { flex: 0.5; }
.col--daemon { flex: 0.4; text-align: center; }
.col--state { flex: 0.7; display: flex; align-items: center; gap: 6px; }
.col--dist { flex: 1; }
.col--pct { flex: 0.5; text-align: right; }
.col--threads { flex: 1.2; display: flex; flex-wrap: wrap; gap: 3px; overflow: visible; }

.row-rank {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  background: var(--ts-bg-elevated);
  border-radius: var(--ts-radius-full);
  font-size: 10px;
  font-weight: 600;
  color: var(--ts-text-muted);
  flex-shrink: 0;
}

.state-dot {
  width: 7px;
  height: 7px;
  border-radius: var(--ts-radius-full);
  flex-shrink: 0;
}

.check-icon {
  color: var(--ts-success);
  font-weight: 600;
}

/* ── Mini state distribution bar ── */
.mini-bar {
  display: flex;
  height: 8px;
  border-radius: 4px;
  overflow: hidden;
  background: #f0f2f5;
  width: 100%;
}

.mini-bar__seg {
  height: 100%;
  min-width: 2px;
  transition: width 0.4s ease;
}

/* ══════════════════════════════════════
   Row 3: Blocked Risks (页面底部)
   ══════════════════════════════════════ */
.blocked-panel {
  background: #ffffff;
  border: 1px solid var(--ts-border-color);
  border-radius: var(--ts-radius-lg);
  padding: var(--ts-space-lg);
  box-shadow: var(--ts-shadow-sm);
}

.blocked-header {
  display: flex;
  align-items: center;
  gap: var(--ts-space-sm);
  margin-bottom: var(--ts-space-md);
}

.health-badge {
  font-size: 10px;
  padding: 3px 10px;
  border-radius: var(--ts-radius-full);
  font-weight: 600;
  letter-spacing: 0.5px;
  text-transform: uppercase;
}

.health-critical .health-badge { background: var(--ts-danger-light); color: var(--ts-danger); }
.health-warning .health-badge  { background: var(--ts-warning-light); color: var(--ts-warning); }
.health-healthy .health-badge  { background: var(--ts-success-light); color: var(--ts-success); }

.risk-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: var(--ts-space-sm);
}

.risk-card {
  border: 1px solid var(--ts-border-color);
  border-left: 3px solid var(--ts-border-color);
  border-radius: var(--ts-radius-md);
  padding: var(--ts-space-md);
  transition: box-shadow var(--ts-transition);
}

.risk-card:hover { box-shadow: var(--ts-shadow-sm); }
.risk-card--critical { border-left-color: var(--ts-danger); background: #fffbfb; }
.risk-card--warning  { border-left-color: var(--ts-warning); background: #fffdf7; }

.risk-card__header {
  display: flex;
  align-items: center;
  gap: var(--ts-space-sm);
  margin-bottom: 4px;
}

.risk-card__title {
  font-weight: 600;
  font-size: var(--ts-font-size-sm);
  color: var(--ts-text-primary);
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.risk-card__badge {
  font-size: 9px;
  padding: 2px 8px;
  border-radius: var(--ts-radius-sm);
  font-weight: 700;
  letter-spacing: 0.5px;
  flex-shrink: 0;
}

.risk-card--critical .risk-card__badge {
  background: #fef2f2;
  color: #dc2626;
  border: 1px solid #fecaca;
}

.risk-card--warning .risk-card__badge {
  background: #fffbeb;
  color: #d97706;
  border: 1px solid #fde68a;
}

.risk-card__desc {
  font-size: var(--ts-font-size-xs);
  color: var(--ts-text-secondary);
  line-height: 1.5;
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.risk-card__lock-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  font-size: var(--ts-font-size-xs);
  color: var(--ts-accent);
  text-decoration: none;
}

.risk-card__lock-link:hover { text-decoration: underline; }

.risk-card__arrow {
  font-size: 10px;
  color: var(--ts-text-muted);
}

.risk-card__threads {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}

/* ── Shared pills ── */
.thread-pill {
  font-size: 9px;
  padding: 1px 6px;
  background: var(--ts-bg-elevated);
  border-radius: var(--ts-radius-full);
  color: var(--ts-text-muted);
  white-space: nowrap;
}

.thread-pill--clickable {
  cursor: pointer;
  color: var(--ts-text-secondary);
  border: 1px solid var(--ts-border-color);
  transition: all var(--ts-transition);
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.thread-pill--clickable:hover {
  color: var(--ts-accent);
  border-color: var(--ts-accent);
  background: var(--ts-accent-light);
}

.thread-pill__arrow {
  font-size: 8px;
  opacity: 0;
  transition: opacity var(--ts-transition);
}

.thread-pill--clickable:hover .thread-pill__arrow {
  opacity: 1;
}

.thread-pill--more {
  color: var(--ts-accent);
  background: var(--ts-accent-light);
}

/* ── Utilities ── */
.text-muted { color: var(--ts-text-muted); }
.text-sm { font-size: var(--ts-font-size-xs); }
.text-danger { color: var(--ts-danger); font-weight: 600; }

/* ══════════════════════════════════════
   Responsive
   ══════════════════════════════════════ */
@media (max-width: 1100px) {
  .overview-grid {
    grid-template-columns: 1fr 1fr;
  }
  .metrics-column {
    grid-column: 1 / -1;
    grid-template-columns: repeat(4, 1fr);
  }
}

@media (max-width: 768px) {
  .overview-grid {
    grid-template-columns: 1fr;
  }
  .metrics-column {
    grid-template-columns: repeat(2, 1fr);
  }
  .risk-grid {
    grid-template-columns: 1fr;
  }
  .tab-bar {
    overflow-x: auto;
  }
}
</style>
