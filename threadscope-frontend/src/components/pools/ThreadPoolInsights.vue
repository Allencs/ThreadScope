<script setup lang="ts">
/**
 * ThreadPoolInsights — 线程池分析视图（v3 重构版）。
 *
 * 每个线程池为可展开行，展开后以 Threads 页面一致的行格式显示
 * 其内部线程实例。点击线程可跳转至 Threads 页面。
 *
 * v3: 新增按池名称搜索过滤功能。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAnalysisStore } from '@/stores/analysisStore'
import { STATE_COLORS, STATE_LABELS, type ThreadState, type ThreadInfo } from '@/types'
import * as api from '@/api/threadscope'

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

// ── Pool expand state ──
const expandedPools = ref<Set<string>>(new Set())

// ── Lazy-loaded thread data per pool ──
const poolThreads = reactive<Record<string, ThreadInfo[]>>({})
const poolLoading = reactive<Record<string, boolean>>({})

onMounted(() => {
  store.loadThreadPools()
})

async function togglePool(poolName: string) {
  if (expandedPools.value.has(poolName)) {
    expandedPools.value.delete(poolName)
  } else {
    expandedPools.value.add(poolName)
    // Lazy load threads for this pool if not cached
    if (!poolThreads[poolName]) {
      poolLoading[poolName] = true
      try {
        const res = await api.fetchThreads(store.analysisId!, {
          poolName,
          page: 1,
          size: 500, // Pool threads are typically bounded
        })
        poolThreads[poolName] = res.threads
      } catch (e) {
        poolThreads[poolName] = []
      } finally {
        poolLoading[poolName] = false
      }
    }
  }
}

function navigateToThread(threadName: string) {
  const analysisId = route.params.analysisId as string
  // Navigate to Threads page with search pre-filled
  store.searchQuery = threadName
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
        :class="{ 'pool-row--expanded': expandedPools.has(pool.poolName) }"
      >
        <!-- Pool Header Row -->
        <div class="pool-row-header" @click="togglePool(pool.poolName)">
          <span class="expand-icon">{{ expandedPools.has(pool.poolName) ? '▼' : '▶' }}</span>

          <span class="pool-name mono">{{ pool.poolName }}</span>

          <span class="pool-type-tag">{{ pool.poolType }}</span>

          <!-- State distribution mini bar -->
          <div class="pool-mini-bar">
            <div
              v-for="(count, state) in pool.stateDistribution"
              :key="state"
              class="pool-mini-segment"
              :style="{
                width: getBarWidth(count as number, pool.totalThreads),
                background: STATE_COLORS[state as ThreadState]
              }"
              :title="`${state}: ${count}`"
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
        <div v-if="expandedPools.has(pool.poolName)" class="pool-thread-list thread-expand-enter">
          <!-- Loading -->
          <div v-if="poolLoading[pool.poolName]" class="pool-loading">
            <div class="spinner"></div>
            <span>Loading threads...</span>
          </div>

          <!-- Thread rows — identical to ThreadExplorer -->
          <template v-else-if="poolThreads[pool.poolName]?.length">
            <div
              v-for="thread in poolThreads[pool.poolName]"
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

@keyframes spin {
  to { transform: rotate(360deg); }
}

.pool-empty {
  padding: var(--ts-space-lg);
  text-align: center;
  color: var(--ts-text-muted);
  font-size: var(--ts-font-size-sm);
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
</style>
