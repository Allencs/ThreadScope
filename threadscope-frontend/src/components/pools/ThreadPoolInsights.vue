<script setup lang="ts">
/**
 * ThreadPoolInsights — 线程池分析视图（v3 重构版）。
 *
 * 每个线程池为可展开行，展开后以 Threads 页面一致的行格式显示
 * 其内部线程实例。点击线程可跳转至 Threads 页面。
 *
 * v3: 新增按池名称搜索过滤功能。
 * v4: 线程池列表 / 展开线程数据缓存至 store（含 sessionStorage 持久化），
 *     切换页面或刷新页面后无需重新加载；展开的线程列表支持按名称 / 栈深度排序。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAnalysisStore } from '@/stores/analysisStore'
import { STATE_COLORS, STATE_LABELS, type ThreadState, type ThreadInfo } from '@/types'

interface PoolTooltipEntry {
  state: ThreadState
  label: string
  color: string
  count: number
  pct: number
}

interface PoolTooltipState {
  visible: boolean
  x: number
  y: number
  poolName: string
  total: number
  entries: PoolTooltipEntry[]
}

const poolTooltip = ref<PoolTooltipState>({
  visible: false,
  x: 0,
  y: 0,
  poolName: '',
  total: 0,
  entries: [],
})

const route = useRoute()
const router = useRouter()
const store = useAnalysisStore()

// ── Search ──
const searchQuery = ref('')

// ── Filtered pools ──
const filteredPools = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return store.threadPools
  return store.threadPools.filter(pool =>
    pool.poolName.toLowerCase().includes(q)
  )
})

// ── Pool expand state (store-backed, 跨页面 / 刷新缓存) ──
function isExpanded(poolName: string): boolean {
  return store.expandedPools.includes(poolName)
}

// ── Thread sorting within an expanded pool ──
type PoolSortKey = 'name' | 'frames'
const sortKey = ref<PoolSortKey>('name')
const sortDir = ref<'asc' | 'desc'>('asc')

function setSort(key: PoolSortKey) {
  if (sortKey.value === key) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = key
    // 名称默认升序（A→Z），栈深度默认降序（深→浅，更易发现热点）。
    sortDir.value = key === 'name' ? 'asc' : 'desc'
  }
}

// ── Thread state filter within an expanded pool ──
const stateFilter = ref<ThreadState[]>([])

function isStateFiltered(state: ThreadState): boolean {
  return stateFilter.value.includes(state)
}

function toggleStateFilter(state: ThreadState) {
  const idx = stateFilter.value.indexOf(state)
  if (idx >= 0) {
    stateFilter.value.splice(idx, 1)
  } else {
    stateFilter.value.push(state)
  }
}

function clearStateFilter() {
  stateFilter.value = []
}

/** 当前池内实际存在的状态（按统一顺序排列），用于渲染筛选项。 */
function availableStates(poolName: string): ThreadState[] {
  const list = store.poolThreads[poolName]
  if (!list) return []
  const present = new Set(list.map(t => t.state))
  return STATE_ORDER.filter(s => present.has(s))
}

function sortedThreads(poolName: string): ThreadInfo[] {
  const list = store.poolThreads[poolName]
  if (!list) return []
  const copy = stateFilter.value.length
    ? list.filter(t => stateFilter.value.includes(t.state))
    : list.slice()
  copy.sort((a, b) => {
    let cmp = 0
    if (sortKey.value === 'name') {
      // 自然排序：让 -1 / -2 / -10 / -100 按数值大小而非字典序排列。
      cmp = a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' })
    } else {
      cmp = (a.stackTrace?.length ?? 0) - (b.stackTrace?.length ?? 0)
    }
    return sortDir.value === 'asc' ? cmp : -cmp
  })
  return copy
}

onMounted(() => {
  // 直接刷新到 pools 页时，父布局可能尚未写入 analysisId，这里兜底从路由读取。
  const analysisIdParam = route.params.analysisId as string
  if (analysisIdParam && !store.analysisId) {
    store.analysisId = analysisIdParam
  }
  store.loadThreadPools()
})

function togglePool(poolName: string) {
  store.togglePoolExpanded(poolName)
  if (isExpanded(poolName)) {
    store.loadPoolThreads(poolName)
  }
}

function navigateToThread(threadName: string) {
  const analysisId = route.params.analysisId as string
  store.openThreadDetailTab(threadName)
  router.push({ name: 'threads', params: { analysisId } })
}

function getBarWidth(count: number, total: number): string {
  return total > 0 ? (count / total * 100) + '%' : '0%'
}

function clearSearch() {
  searchQuery.value = ''
}

/** State badge color mapping — identical to ThreadExplorer */
const stateBadgeStyles: Record<string, { color: string; bg: string }> = {
  RUNNABLE:      { color: '#16a34a', bg: '#f0fdf4' },
  BLOCKED:       { color: '#dc2626', bg: '#fef2f2' },
  WAITING:       { color: '#d97706', bg: '#fffbeb' },
  TIMED_WAITING: { color: '#2563eb', bg: '#eff6ff' },
}
const defaultBadgeStyle = { color: '#9ca3af', bg: '#f4f5f7' }

function getStateBadgeStyle(state: ThreadState) {
  return stateBadgeStyles[state] || defaultBadgeStyle
}

function getStateBadgeClass(state: ThreadState): string {
  return state === 'BLOCKED' ? 'state-pulse-danger' : ''
}

// ── Pool mini-bar hover tooltip ──
const STATE_ORDER: ThreadState[] = [
  'RUNNABLE',
  'BLOCKED',
  'WAITING',
  'TIMED_WAITING',
  'NEW',
  'TERMINATED',
  'UNKNOWN',
]

function buildTooltipEntries(
  distribution: Record<string, number>,
  total: number,
): PoolTooltipEntry[] {
  const entries: PoolTooltipEntry[] = []
  for (const state of STATE_ORDER) {
    const count = distribution[state] ?? 0
    if (count <= 0) continue
    entries.push({
      state,
      label: STATE_LABELS[state],
      color: STATE_COLORS[state],
      count,
      pct: total > 0 ? count / total : 0,
    })
  }
  return entries
}

function showPoolTooltip(ev: MouseEvent, pool: { poolName: string; totalThreads: number; stateDistribution: Record<string, number> }) {
  poolTooltip.value = {
    visible: true,
    x: ev.clientX,
    y: ev.clientY,
    poolName: pool.poolName,
    total: pool.totalThreads,
    entries: buildTooltipEntries(pool.stateDistribution, pool.totalThreads),
  }
}

function movePoolTooltip(ev: MouseEvent) {
  if (!poolTooltip.value.visible) return
  poolTooltip.value.x = ev.clientX
  poolTooltip.value.y = ev.clientY
}

function hidePoolTooltip() {
  poolTooltip.value.visible = false
}
</script>

<template>
  <div class="pool-insights">
    <div class="page-header">
      <h2 class="page-title">Thread Pools</h2>
      <span class="pool-count mono">{{ filteredPools.length }}<template v-if="searchQuery.trim()"> / {{ store.threadPools.length }}</template> pools detected</span>
    </div>

    <!-- Search Bar -->
    <div class="search-bar">
      <div class="search-input-wrapper">
        <svg class="search-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="11" cy="11" r="8"/>
          <line x1="21" y1="21" x2="16.65" y2="16.65"/>
        </svg>
        <input
          v-model="searchQuery"
          type="text"
          class="search-input mono"
          placeholder="Search pool by name..."
        />
        <button
          v-if="searchQuery"
          class="search-clear"
          @click="clearSearch"
          title="Clear search"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>
    </div>

    <!-- Pool List -->
    <div class="pool-list">
      <div
        v-for="pool in filteredPools"
        :key="pool.poolName"
        class="pool-row"
        :class="{ 'pool-row--expanded': isExpanded(pool.poolName) }"
      >
        <!-- Pool Header Row -->
        <div class="pool-row-header" @click="togglePool(pool.poolName)">
          <span class="expand-icon">
            <span v-if="store.poolThreadsLoading[pool.poolName]" class="spinner spinner--inline"></span>
            <template v-else>{{ isExpanded(pool.poolName) ? '▼' : '▶' }}</template>
          </span>

          <span class="pool-name mono">{{ pool.poolName }}</span>

          <span class="pool-type-tag">{{ pool.poolType }}</span>

          <span v-if="store.poolThreadsLoading[pool.poolName]" class="pool-loading-tag">
            Loading…
          </span>

          <!-- State distribution mini bar -->
          <div
            class="pool-mini-bar"
            @mouseenter="showPoolTooltip($event, pool)"
            @mousemove="movePoolTooltip"
            @mouseleave="hidePoolTooltip"
          >
            <div
              v-for="(count, state) in pool.stateDistribution"
              :key="state"
              class="pool-mini-segment"
              :style="{
                width: getBarWidth(count as number, pool.totalThreads),
                background: STATE_COLORS[state as ThreadState]
              }"
            ></div>
          </div>

          <!-- State badges summary -->
          <div class="pool-state-summary">
            <span
              v-for="(count, state) in pool.stateDistribution"
              :key="state"
              class="pool-state-chip"
              :style="{
                color: (stateBadgeStyles[state as string] || defaultBadgeStyle).color,
                backgroundColor: (stateBadgeStyles[state as string] || defaultBadgeStyle).bg,
              }"
            >
              {{ count }}
            </span>
          </div>

          <span class="pool-thread-count mono">{{ pool.totalThreads }} threads</span>
        </div>

        <!-- Expanded: Thread Instances -->
        <div v-if="isExpanded(pool.poolName)" class="pool-thread-list thread-expand-enter">
          <!-- Loading -->
          <div v-if="store.poolThreadsLoading[pool.poolName]" class="pool-loading">
            <div class="spinner"></div>
            <div class="pool-loading-text">
              <span>Loading {{ pool.totalThreads }} threads…</span>
              <span class="pool-loading-hint">Parsing stack traces, this may take a moment for large pools.</span>
            </div>
          </div>

          <!-- Thread rows — identical to ThreadExplorer -->
          <template v-else-if="store.poolThreads[pool.poolName]?.length">
            <!-- Sort + Filter toolbar -->
            <div class="thread-sort-bar">
              <span class="thread-sort-label">Sort by</span>
              <button
                class="sort-btn"
                :class="{ 'sort-btn--active': sortKey === 'name' }"
                @click="setSort('name')"
              >
                Name
                <span v-if="sortKey === 'name'" class="sort-dir">{{ sortDir === 'asc' ? '↑' : '↓' }}</span>
              </button>
              <button
                class="sort-btn"
                :class="{ 'sort-btn--active': sortKey === 'frames' }"
                @click="setSort('frames')"
              >
                Frames
                <span v-if="sortKey === 'frames'" class="sort-dir">{{ sortDir === 'asc' ? '↑' : '↓' }}</span>
              </button>

              <span class="thread-sort-divider"></span>

              <span class="thread-sort-label">State</span>
              <button
                v-for="state in availableStates(pool.poolName)"
                :key="state"
                class="filter-chip"
                :class="{ 'filter-chip--active': isStateFiltered(state) }"
                :style="isStateFiltered(state) ? {
                  color: getStateBadgeStyle(state).color,
                  backgroundColor: getStateBadgeStyle(state).bg,
                  borderColor: getStateBadgeStyle(state).color,
                } : {}"
                @click="toggleStateFilter(state)"
              >
                <span class="filter-chip-dot" :style="{ background: STATE_COLORS[state] }"></span>
                {{ STATE_LABELS[state] }}
              </button>
              <button
                v-if="stateFilter.length"
                class="filter-clear"
                @click="clearStateFilter"
                title="Clear state filter"
              >
                Clear
              </button>
            </div>

            <div
              v-for="thread in sortedThreads(pool.poolName)"
              :key="thread.name"
              class="thread-row"
            >
              <div class="thread-row-header" @click="navigateToThread(thread.name)">
                <span
                  class="state-badge"
                  :class="getStateBadgeClass(thread.state)"
                  :style="{
                    color: getStateBadgeStyle(thread.state).color,
                    backgroundColor: getStateBadgeStyle(thread.state).bg,
                  }"
                >
                  {{ STATE_LABELS[thread.state] }}
                </span>

                <span class="thread-name mono">{{ thread.name }}</span>

                <span v-if="thread.daemon" class="daemon-tag">daemon</span>

                <span
                  v-if="thread.stackTrace?.length"
                  class="stack-depth-badge mono"
                >
                  <svg class="frames-icon" width="10" height="10" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2">
                    <line x1="2" y1="4" x2="14" y2="4"/><line x1="2" y1="8" x2="14" y2="8"/><line x1="2" y1="12" x2="10" y2="12"/>
                  </svg>
                  {{ thread.stackTrace.length }} frames
                </span>

                <span class="thread-nid mono">nid={{ thread.nid }}</span>

                <span v-if="thread.lockActions?.some(a => a.lockAddress)" class="lock-icon" title="Has lock activity">🔒</span>

                <span class="view-link" title="View in Threads page">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>
                    <polyline points="15 3 21 3 21 9"/>
                    <line x1="10" y1="14" x2="21" y2="3"/>
                  </svg>
                </span>
              </div>
            </div>

            <!-- Filtered to empty -->
            <div v-if="!sortedThreads(pool.poolName).length" class="pool-empty">
              No threads match the selected state filter.
              <button class="filter-clear" @click="clearStateFilter">Clear filter</button>
            </div>
          </template>

          <!-- Empty -->
          <div v-else class="pool-empty">
            No thread data available for this pool.
          </div>
        </div>
      </div>
    </div>

    <!-- Global Empty (no pools at all) -->
    <div v-if="store.threadPools.length === 0" class="empty-state">
      <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="var(--ts-text-muted)" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round">
        <rect x="3" y="3" width="7" height="7" rx="1.5"/>
        <rect x="14" y="3" width="7" height="7" rx="1.5"/>
        <rect x="3" y="14" width="7" height="7" rx="1.5"/>
        <rect x="14" y="14" width="7" height="7" rx="1.5"/>
      </svg>
      <p>No thread pools detected.</p>
    </div>

    <!-- No search results -->
    <div v-else-if="filteredPools.length === 0 && searchQuery.trim()" class="empty-state">
      <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="var(--ts-text-muted)" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="11" cy="11" r="8"/>
        <line x1="21" y1="21" x2="16.65" y2="16.65"/>
      </svg>
      <p>No pools matching "<span class="mono">{{ searchQuery }}</span>"</p>
      <button class="clear-btn" @click="clearSearch">Clear search</button>
    </div>

    <!-- Pool mini-bar hover tooltip (shared) -->
    <Transition name="pool-tooltip-fade">
      <div
        v-if="poolTooltip.visible"
        class="pool-tooltip"
        :style="{
          left: poolTooltip.x + 'px',
          top: poolTooltip.y + 'px',
        }"
      >
        <div class="pool-tooltip__header">
          <span class="pool-tooltip__title mono">{{ poolTooltip.poolName }}</span>
          <span class="pool-tooltip__total mono">{{ poolTooltip.total }} threads</span>
        </div>
        <div class="pool-tooltip__divider"></div>
        <div class="pool-tooltip__list">
          <div
            v-for="entry in poolTooltip.entries"
            :key="entry.state"
            class="pool-tooltip__item"
          >
            <span class="pool-tooltip__dot" :style="{ background: entry.color }"></span>
            <span class="pool-tooltip__label">{{ entry.label }}</span>
            <span class="pool-tooltip__count mono">{{ entry.count }}</span>
            <span class="pool-tooltip__sep">·</span>
            <span class="pool-tooltip__pct mono">{{ (entry.pct * 100).toFixed(1) }}%</span>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.pool-insights {
  display: flex;
  flex-direction: column;
  gap: var(--ts-space-md);
  height: 100%;
}

.page-header {
  display: flex;
  align-items: baseline;
  gap: var(--ts-space-md);
}

.page-title {
  font-size: var(--ts-font-size-xl);
  font-weight: 600;
}

.pool-count {
  font-size: var(--ts-font-size-sm);
  color: var(--ts-text-muted);
}

/* ── Search Bar ── */
.search-bar {
  display: flex;
  gap: var(--ts-space-sm);
}

.search-input-wrapper {
  position: relative;
  flex: 1;
  max-width: 400px;
}

.search-icon {
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--ts-text-muted);
  pointer-events: none;
}

.search-input {
  width: 100%;
  padding: 8px 32px 8px 34px;
  font-size: var(--ts-font-size-sm);
  border: 1px solid var(--ts-border-color);
  border-radius: var(--ts-radius-md);
  background: var(--ts-bg-surface);
  color: var(--ts-text-primary);
  outline: none;
  transition: border-color var(--ts-transition), box-shadow var(--ts-transition);
}

.search-input::placeholder {
  color: var(--ts-text-muted);
}

.search-input:focus {
  border-color: var(--ts-accent);
  box-shadow: 0 0 0 3px var(--ts-accent-light);
}

.search-clear {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: var(--ts-radius-full);
  background: var(--ts-bg-elevated);
  color: var(--ts-text-muted);
  cursor: pointer;
  transition: all var(--ts-transition);
}

.search-clear:hover {
  background: var(--ts-border-color);
  color: var(--ts-text-primary);
}

/* ── Pool List ── */
.pool-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  border-radius: var(--ts-radius-lg);
  border: var(--ts-border);
  background: var(--ts-bg-surface);
}

.pool-row {
  border-bottom: 1px solid var(--ts-border-color);
}

.pool-row:last-child {
  border-bottom: none;
}

.pool-row:first-child {
  border-radius: var(--ts-radius-lg) var(--ts-radius-lg) 0 0;
}

.pool-row:last-child {
  border-radius: 0 0 var(--ts-radius-lg) var(--ts-radius-lg);
}

/* ── Pool Header ── */
.pool-row-header {
  display: flex;
  align-items: center;
  gap: var(--ts-space-sm);
  padding: 12px var(--ts-space-md);
  cursor: pointer;
  transition: background var(--ts-transition);
}

.pool-row-header:hover {
  background: var(--ts-bg-hover);
}

.pool-row--expanded > .pool-row-header {
  background: var(--ts-bg-inset);
}

.expand-icon {
  font-size: 10px;
  color: var(--ts-text-muted);
  width: 16px;
  text-align: center;
  flex-shrink: 0;
}

.pool-name {
  font-size: var(--ts-font-size-sm);
  font-weight: 600;
  color: var(--ts-text-primary);
  white-space: nowrap;
}

.pool-type-tag {
  font-size: 10px;
  padding: 1px 7px;
  background: var(--ts-accent-light);
  color: var(--ts-accent);
  border-radius: var(--ts-radius-full);
  font-weight: 500;
  flex-shrink: 0;
  letter-spacing: 0.2px;
}

/* ── Mini state distribution bar ── */
.pool-mini-bar {
  display: flex;
  height: 6px;
  min-width: 80px;
  max-width: 160px;
  flex: 1;
  border-radius: var(--ts-radius-full);
  overflow: hidden;
  background: var(--ts-bg-elevated);
}

.pool-mini-segment {
  transition: width 0.6s cubic-bezier(0.16, 1, 0.3, 1);
}

.pool-state-summary {
  display: flex;
  gap: 3px;
  flex-shrink: 0;
}

.pool-state-chip {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: var(--ts-radius-full);
  line-height: 1.4;
}

.pool-thread-count {
  font-size: var(--ts-font-size-xs);
  color: var(--ts-text-muted);
  margin-left: auto;
  white-space: nowrap;
  flex-shrink: 0;
}

/* ── Expanded thread list ── */
.pool-thread-list {
  border-top: 1px solid var(--ts-border-color);
  background: var(--ts-bg-primary);
}

.pool-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--ts-space-sm);
  padding: var(--ts-space-lg);
  color: var(--ts-text-muted);
  font-size: var(--ts-font-size-sm);
}

.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid var(--ts-border-color);
  border-top-color: var(--ts-accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

.spinner--inline {
  display: inline-block;
  width: 11px;
  height: 11px;
  border-width: 1.5px;
  vertical-align: middle;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.pool-loading-text {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  text-align: left;
}

.pool-loading-hint {
  font-size: var(--ts-font-size-xs);
  color: var(--ts-text-muted);
  opacity: 0.8;
}

.pool-loading-tag {
  font-size: 10px;
  font-weight: 500;
  padding: 1px 8px;
  border-radius: var(--ts-radius-full);
  background: var(--ts-accent-light);
  color: var(--ts-accent);
  flex-shrink: 0;
  letter-spacing: 0.2px;
  animation: pool-loading-pulse 1.2s ease-in-out infinite;
}

@keyframes pool-loading-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.pool-empty {
  padding: var(--ts-space-lg);
  text-align: center;
  color: var(--ts-text-muted);
  font-size: var(--ts-font-size-sm);
}

/* ── Thread sort toolbar ── */
.thread-sort-bar {
  display: flex;
  align-items: center;
  gap: var(--ts-space-xs);
  padding: 6px var(--ts-space-md) 6px calc(var(--ts-space-md) + 16px + var(--ts-space-sm));
  background: var(--ts-bg-inset);
  border-bottom: 1px solid var(--ts-border-color);
}

.thread-sort-label {
  font-size: var(--ts-font-size-xs);
  color: var(--ts-text-muted);
  margin-right: 2px;
}

.sort-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 10px;
  font-size: var(--ts-font-size-xs);
  font-weight: 500;
  color: var(--ts-text-secondary);
  background: var(--ts-bg-surface);
  border: 1px solid var(--ts-border-color);
  border-radius: var(--ts-radius-full);
  cursor: pointer;
  transition: all var(--ts-transition);
}

.sort-btn:hover {
  border-color: var(--ts-accent);
  color: var(--ts-accent);
}

.sort-btn--active {
  color: var(--ts-accent);
  background: var(--ts-accent-light);
  border-color: var(--ts-accent);
}

.sort-dir {
  font-size: 11px;
  line-height: 1;
}

.thread-sort-divider {
  width: 1px;
  align-self: stretch;
  margin: 2px 4px;
  background: var(--ts-border-color);
}

.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 9px;
  font-size: var(--ts-font-size-xs);
  font-weight: 600;
  letter-spacing: 0.2px;
  color: var(--ts-text-secondary);
  background: var(--ts-bg-surface);
  border: 1px solid var(--ts-border-color);
  border-radius: var(--ts-radius-full);
  cursor: pointer;
  transition: all var(--ts-transition);
}

.filter-chip:hover {
  border-color: var(--ts-text-muted);
  color: var(--ts-text-primary);
}

.filter-chip--active {
  font-weight: 700;
}

.filter-chip-dot {
  width: 7px;
  height: 7px;
  border-radius: var(--ts-radius-full);
  flex-shrink: 0;
}

.filter-clear {
  padding: 2px 10px;
  font-size: var(--ts-font-size-xs);
  font-weight: 500;
  color: var(--ts-text-muted);
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--ts-radius-full);
  cursor: pointer;
  transition: all var(--ts-transition);
}

.filter-clear:hover {
  color: var(--ts-accent);
  background: var(--ts-accent-light);
}

/* ── Thread rows (consistent with ThreadExplorer) ── */
.thread-row {
  border-bottom: 1px solid var(--ts-border-color);
  background: var(--ts-bg-surface);
}

.thread-row:last-child {
  border-bottom: none;
}

.thread-row-header {
  display: flex;
  align-items: center;
  gap: var(--ts-space-sm);
  padding: 8px var(--ts-space-md) 8px calc(var(--ts-space-md) + 16px + var(--ts-space-sm));
  cursor: pointer;
  transition: background var(--ts-transition);
}

.thread-row-header:hover {
  background: var(--ts-bg-hover);
}

.state-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: var(--ts-radius-full);
  border: none;
  flex-shrink: 0;
  letter-spacing: 0.3px;
  line-height: 1.45;
}

.thread-name {
  font-size: var(--ts-font-size-sm);
  color: var(--ts-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}

.daemon-tag {
  font-size: 10px;
  padding: 1px 6px;
  background: var(--ts-bg-inset);
  border-radius: var(--ts-radius-full);
  color: var(--ts-text-muted);
  flex-shrink: 0;
  line-height: 1.45;
}

.stack-depth-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 10px;
  padding: 1px 8px 1px 5px;
  background: var(--ts-accent-light);
  border: 1px solid #dbeafe;
  border-radius: var(--ts-radius-full);
  color: var(--ts-accent);
  flex-shrink: 0;
  line-height: 1.45;
  margin-left: auto;
}

.frames-icon {
  flex-shrink: 0;
  opacity: 0.7;
}

.thread-nid {
  font-size: var(--ts-font-size-xs);
  color: var(--ts-text-muted);
  flex-shrink: 0;
}

/* Push nid right when no stack-depth-badge */
.thread-row-header > .thread-nid:not(.stack-depth-badge ~ .thread-nid) {
  margin-left: auto;
}

.lock-icon {
  flex-shrink: 0;
  font-size: 12px;
  opacity: 0.6;
}

.view-link {
  flex-shrink: 0;
  color: var(--ts-text-muted);
  transition: color var(--ts-transition);
  display: flex;
  align-items: center;
}

.thread-row-header:hover .view-link {
  color: var(--ts-accent);
}

/* ── Empty State ── */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ts-space-md);
  padding: var(--ts-space-2xl);
  text-align: center;
  color: var(--ts-text-muted);
  font-size: var(--ts-font-size-sm);
}

.clear-btn {
  padding: 6px 16px;
  font-size: var(--ts-font-size-sm);
  font-weight: 500;
  color: var(--ts-accent);
  background: var(--ts-accent-light);
  border: 1px solid transparent;
  border-radius: var(--ts-radius-md);
  cursor: pointer;
  transition: all var(--ts-transition);
}

.clear-btn:hover {
  background: var(--ts-accent);
  color: #fff;
}

/* ══════════════════════════════════════
   Pool Mini-bar Hover Tooltip
   (style aligned with Analysis Overview donut tooltip)
   ══════════════════════════════════════ */
.pool-tooltip {
  position: fixed;
  z-index: 9500;
  transform: translate(14px, -50%);
  pointer-events: none;
  background: rgba(17, 24, 39, 0.96);
  color: #ffffff;
  padding: 10px 12px;
  border-radius: 8px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.22);
  min-width: 200px;
  max-width: 320px;
  backdrop-filter: blur(6px);
}

.pool-tooltip__header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
}

.pool-tooltip__title {
  font-size: 12px;
  font-weight: 600;
  color: #ffffff;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 220px;
}

.pool-tooltip__total {
  font-size: 11px;
  color: #93c5fd;
  flex-shrink: 0;
}

.pool-tooltip__divider {
  height: 1px;
  background: rgba(255, 255, 255, 0.08);
  margin: 0 -12px 6px;
}

.pool-tooltip__list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.pool-tooltip__item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  line-height: 1.5;
}

.pool-tooltip__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.pool-tooltip__label {
  flex: 1;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.3px;
  text-transform: uppercase;
  color: #e5e7eb;
}

.pool-tooltip__count {
  font-size: 12px;
  font-weight: 600;
  color: #ffffff;
}

.pool-tooltip__sep {
  color: #6b7280;
}

.pool-tooltip__pct {
  font-size: 11px;
  color: #93c5fd;
  min-width: 38px;
  text-align: right;
}

.pool-tooltip-fade-enter-active,
.pool-tooltip-fade-leave-active {
  transition: opacity 0.12s ease, transform 0.12s ease;
}
.pool-tooltip-fade-enter-from,
.pool-tooltip-fade-leave-to {
  opacity: 0;
}
</style>
