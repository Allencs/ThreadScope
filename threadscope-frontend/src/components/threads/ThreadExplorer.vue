<script setup lang="ts">
/**
 * ThreadExplorer — 线程列表主视图。
 * 支持多维度搜索、状态过滤、虚拟滚动展示。
 *
 * v2: 锁操作按 frameIndex 内联显示在对应堆栈帧之后，并支持点击跳转到 Locks 页面。
 */
import { ref, onMounted, watch, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAnalysisStore } from '@/stores/analysisStore'
import { STATE_COLORS, STATE_LABELS, type ThreadState, type ThreadInfo, type LockAction } from '@/types'

const store = useAnalysisStore()
const route = useRoute()
const router = useRouter()

const searchInput = ref('')
const expandedThreads = ref<Set<string>>(new Set())
const currentPage = ref(1)
const pageSize = 50

onMounted(() => {
  if (store.searchQuery) {
    searchInput.value = store.searchQuery
  }
  store.loadThreads(1, pageSize)
})

// Debounced search
let searchTimer: ReturnType<typeof setTimeout>
watch(searchInput, (val) => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    store.searchQuery = val
    currentPage.value = 1
    store.loadThreads(1, pageSize)
  }, 300)
})

const stateFilterOptions: ThreadState[] = ['RUNNABLE', 'BLOCKED', 'WAITING', 'TIMED_WAITING']

function toggleStateFilter(state: ThreadState) {
  const idx = store.stateFilter.indexOf(state)
  if (idx >= 0) {
    store.stateFilter.splice(idx, 1)
  } else {
    store.stateFilter.push(state)
  }
  currentPage.value = 1
  store.loadThreads(1, pageSize)
}

function clearSearch() {
  searchInput.value = ''
  store.searchQuery = ''
  currentPage.value = 1
  store.loadThreads(1, pageSize)
}

function toggleSort() {
  store.sortBy = store.sortBy === 'stackDepth' ? 'default' : 'stackDepth'
  currentPage.value = 1
  store.loadThreads(1, pageSize)
}

function toggleExpand(threadName: string) {
  if (expandedThreads.value.has(threadName)) {
    expandedThreads.value.delete(threadName)
  } else {
    expandedThreads.value.add(threadName)
  }
}

function loadPage(page: number) {
  currentPage.value = page
  store.loadThreads(page, pageSize)
}

const totalPages = computed(() => Math.ceil(store.threadTotal / pageSize))

function getStateBadgeClass(state: ThreadState): string {
  const map: Record<string, string> = {
    BLOCKED: 'state-pulse-danger',
    RUNNABLE: '',
    WAITING: '',
    TIMED_WAITING: '',
  }
  return map[state] || ''
}

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

// ── Lock Action helpers ──

/**
 * 获取某个 frame 之后关联的所有 lock actions。
 * 利用后端返回的 frameIndex 精确定位。
 */
function getLockActionsForFrame(thread: ThreadInfo, frameIdx: number): LockAction[] {
  return thread.lockActions.filter(la => la.frameIndex === frameIdx)
}

function getLockActionLabel(lock: LockAction): string {
  switch (lock.type) {
    case 'Held': return '- locked'
    case 'WaitingToLock': return '- waiting to lock'
    case 'ParkingToWaitFor': return '- parking to wait for'
    case 'WaitingOn': return '- waiting on'
    default: return '- lock operation'
  }
}

function getLockActionCssClass(lock: LockAction): string {
  switch (lock.type) {
    case 'Held': return 'lock-action--held'
    case 'WaitingToLock': return 'lock-action--blocked'
    case 'ParkingToWaitFor': return 'lock-action--parking'
    case 'WaitingOn': return 'lock-action--waiting'
    default: return ''
  }
}

function navigateToLock(lockAddress: string) {
  store.highlightLockAddress = lockAddress
  router.push({ name: 'locks', params: { analysisId: route.params.analysisId } })
}
</script>

<template>
  <div class="thread-explorer">
    <!-- Search & Filter Bar -->
    <div class="search-bar">
      <div class="search-input-wrapper">
        <svg class="search-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
        </svg>
        <input
          v-model="searchInput"
          class="search-input mono"
          placeholder="Search by thread name, class, method, nid, lock address..."
        />
        <button
          v-if="searchInput"
          class="search-clear-btn"
          @click="clearSearch"
          title="Clear search"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>

      <div class="state-filters">
        <button
          v-for="state in stateFilterOptions"
          :key="state"
          class="state-filter-btn"
          :class="{ 'state-filter-btn--active': store.stateFilter.includes(state) }"
          :style="{ '--state-color': STATE_COLORS[state] }"
          @click="toggleStateFilter(state)"
        >
          <span class="state-dot"></span>
          {{ STATE_LABELS[state] }}
        </button>
      </div>

      <button
        class="sort-btn"
        :class="{ 'sort-btn--active': store.sortBy === 'stackDepth' }"
        @click="toggleSort"
        title="Sort by stack depth (deepest first)"
      >
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
          <line x1="2" y1="4" x2="14" y2="4"/>
          <line x1="2" y1="8" x2="10" y2="8"/>
          <line x1="2" y1="12" x2="6" y2="12"/>
        </svg>
        Stack Depth
      </button>

      <span class="thread-count mono">{{ store.threadTotal }} threads</span>
    </div>

    <!-- Thread List -->
    <div class="thread-list">
      <div
        v-for="thread in store.threads"
        :key="thread.name"
        class="thread-row"
        :class="{ 'thread-row--expanded': expandedThreads.has(thread.name) }"
      >
        <!-- Row Header -->
        <div class="thread-row-header" @click="toggleExpand(thread.name)">
          <span class="expand-icon">{{ expandedThreads.has(thread.name) ? '▼' : '▶' }}</span>

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

          <span class="thread-nid mono" :title="'nid=' + thread.nid + ' (decimal: ' + thread.nidDecimal + ')'">
            nid={{ thread.nid }}
          </span>

          <span v-if="thread.lockActions?.some(a => a.lockAddress)" class="lock-icon" title="Has lock activity">🔒</span>
        </div>

        <!-- Expanded Detail -->
        <div v-if="expandedThreads.has(thread.name)" class="thread-detail thread-expand-enter">
          <!-- Meta Info -->
          <div class="detail-meta">
            <span><b>Thread #:</b> {{ thread.threadNumber }}</span>
            <span><b>Priority:</b> {{ thread.priority }}</span>
            <span><b>OS Priority:</b> {{ thread.osPriority }}</span>
            <span v-if="thread.cpuTime"><b>CPU:</b> {{ thread.cpuTime }}</span>
            <span v-if="thread.elapsed"><b>Elapsed:</b> {{ thread.elapsed }}</span>
            <span><b>tid:</b> <code>{{ thread.tid }}</code></span>
            <span><b>nid:</b> <code>{{ thread.nid }}</code> ({{ thread.nidDecimal }})</span>
            <span v-if="thread.stateDetail"><b>Detail:</b> {{ thread.stateDetail }}</span>
          </div>

          <!-- Stack Trace with inline lock actions -->
          <div v-if="thread.stackTrace?.length" class="stack-trace">
            <div class="stack-header">STACK TRACE ({{ thread.stackTrace.length }} FRAMES)</div>

            <template v-for="(frame, idx) in thread.stackTrace" :key="idx">
              <!-- Stack Frame -->
              <div
                class="stack-frame mono"
                :class="{ 'stack-frame--jdk': frame.className.startsWith('java.') || frame.className.startsWith('sun.') || frame.className.startsWith('jdk.') }"
              >
                <span class="frame-at">at </span>
                <span class="frame-class">{{ frame.className }}</span>.<span class="frame-method">{{ frame.methodName }}</span>(<span class="frame-source">{{ frame.source }}</span>)
              </div>

              <!-- Lock actions associated with this frame (inline, right after the frame) -->
              <div
                v-for="(lock, lockIdx) in getLockActionsForFrame(thread, idx)"
                :key="'lock-' + idx + '-' + lockIdx"
                class="lock-action mono"
                :class="getLockActionCssClass(lock)"
                @click.stop="navigateToLock(lock.lockAddress)"
                :title="'Click to view lock ' + lock.lockAddress + ' in Locks page'"
              >
                <span class="lock-type">{{ getLockActionLabel(lock) }}</span>
                &lt;<span class="lock-addr">{{ lock.lockAddress }}</span>&gt;
                (a {{ lock.lockClassName }})
                <span class="lock-goto">→</span>
              </div>
            </template>
          </div>

          <!-- Ownable Synchronizers -->
          <div v-if="thread.ownableSynchronizers?.length" class="ownable-sync">
            <div class="sync-header">Locked Ownable Synchronizers:</div>
            <div v-for="(sync, idx) in thread.ownableSynchronizers" :key="idx" class="sync-entry mono">
              - {{ sync }}
            </div>
          </div>
        </div>
      </div>

      <!-- Empty State -->
      <div v-if="store.threads.length === 0" class="empty-state">
        No threads found matching your criteria.
      </div>
    </div>

    <!-- Pagination -->
    <div v-if="totalPages > 1" class="pagination">
      <button @click="loadPage(currentPage - 1)" :disabled="currentPage <= 1" class="page-btn">&lt;</button>
      <span class="page-info mono">Page {{ currentPage }} / {{ totalPages }}</span>
      <button @click="loadPage(currentPage + 1)" :disabled="currentPage >= totalPages" class="page-btn">&gt;</button>
    </div>
  </div>
</template>

<style scoped>
.thread-explorer {
  display: flex;
  flex-direction: column;
  gap: var(--ts-space-md);
  height: 100%;
}

/* ── Search Bar ── */
.search-bar {
  display: flex;
  align-items: center;
  gap: var(--ts-space-md);
  flex-wrap: wrap;
}

.search-input-wrapper {
  flex: 1;
  min-width: 300px;
  position: relative;
}

.search-icon {
  position: absolute;
  left: 12px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--ts-text-muted);
}

.search-input {
  width: 100%;
  padding: 8px 32px 8px 36px;
  background: var(--ts-bg-surface);
  border: var(--ts-border);
  border-radius: var(--ts-radius-md);
  color: var(--ts-text-primary);
  font-size: var(--ts-font-size-sm);
  outline: none;
  transition: border-color var(--ts-transition), box-shadow var(--ts-transition);
}

.search-input:focus {
  border-color: var(--ts-accent);
  box-shadow: var(--ts-shadow-ring);
}

.search-input::placeholder {
  color: var(--ts-text-muted);
}

.search-clear-btn {
  position: absolute;
  right: 8px;
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
  padding: 0;
}

.search-clear-btn:hover {
  background: var(--ts-bg-active);
  color: var(--ts-text-primary);
}

.state-filters {
  display: flex;
  gap: var(--ts-space-xs);
}

.state-filter-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: var(--ts-border);
  border-radius: var(--ts-radius-full);
  background: transparent;
  color: var(--ts-text-secondary);
  font-size: var(--ts-font-size-xs);
  cursor: pointer;
  transition: all var(--ts-transition);
}

.state-filter-btn:hover {
  border-color: var(--state-color);
}

.state-filter-btn--active {
  background: color-mix(in srgb, var(--state-color) 15%, transparent);
  border-color: var(--state-color);
  color: var(--state-color);
}

.state-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--state-color);
}

.thread-count {
  color: var(--ts-text-muted);
  font-size: var(--ts-font-size-sm);
  white-space: nowrap;
}

/* ── Sort Button ── */
.sort-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: var(--ts-border);
  border-radius: var(--ts-radius-full);
  background: transparent;
  color: var(--ts-text-secondary);
  font-size: var(--ts-font-size-xs);
  cursor: pointer;
  transition: all var(--ts-transition);
  white-space: nowrap;
  flex-shrink: 0;
}

.sort-btn:hover {
  border-color: var(--ts-accent);
  color: var(--ts-accent);
}

.sort-btn--active {
  background: var(--ts-accent-light);
  border-color: var(--ts-accent);
  color: var(--ts-accent);
  font-weight: 500;
}

/* ── Thread List ── */
.thread-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  border-radius: var(--ts-radius-lg);
  border: var(--ts-border);
  background: var(--ts-bg-surface);
}

.thread-row {
  background: var(--ts-bg-surface);
  border-bottom: 1px solid var(--ts-border-color);
}

.thread-row:last-child {
  border-bottom: none;
}

.thread-row:first-child {
  border-radius: var(--ts-radius-lg) var(--ts-radius-lg) 0 0;
}

.thread-row:last-child {
  border-radius: 0 0 var(--ts-radius-lg) var(--ts-radius-lg);
}

.thread-row--expanded {
  background: var(--ts-bg-surface);
}

.thread-row-header {
  display: flex;
  align-items: center;
  gap: var(--ts-space-sm);
  padding: 10px var(--ts-space-md);
  cursor: pointer;
  transition: background var(--ts-transition);
}

.thread-row-header:hover {
  background: var(--ts-bg-hover);
}

.expand-icon {
  font-size: 10px;
  color: var(--ts-text-muted);
  width: 16px;
  text-align: center;
  flex-shrink: 0;
  transition: color var(--ts-transition);
}

.thread-row-header:hover .expand-icon {
  color: var(--ts-text-secondary);
}

/* ── State Badge ── */
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
  color: #1a1d23;
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

/* ── Stack Depth Badge ── */
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

.thread-row-header > .thread-nid:not(.stack-depth-badge ~ .thread-nid) {
  margin-left: auto;
}

.lock-icon {
  flex-shrink: 0;
  font-size: 12px;
  opacity: 0.6;
}

/* ── Thread Detail (Expanded) ── */
.thread-detail {
  padding: 0 var(--ts-space-md) var(--ts-space-md) calc(var(--ts-space-md) + 16px + var(--ts-space-sm));
  border-top: 1px solid var(--ts-border-color);
  background: var(--ts-bg-surface);
}

.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ts-space-sm) var(--ts-space-lg);
  padding: var(--ts-space-sm) 0;
  font-size: var(--ts-font-size-xs);
  color: var(--ts-text-secondary);
  border-bottom: 1px solid var(--ts-border-color);
  margin-bottom: var(--ts-space-sm);
}

.detail-meta b {
  color: var(--ts-text-primary);
  font-weight: 600;
}

.detail-meta code {
  color: var(--ts-accent);
  font-size: var(--ts-font-size-xs);
  background: var(--ts-accent-light);
  padding: 0 4px;
  border-radius: var(--ts-radius-xs);
}

.stack-trace {
  margin-top: var(--ts-space-sm);
}

.stack-header {
  font-size: var(--ts-font-size-xs);
  color: var(--ts-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: var(--ts-space-xs);
  font-weight: 600;
}

.stack-frame {
  font-size: 12px;
  padding: 2px 0;
  color: var(--ts-text-primary);
  line-height: 1.7;
}

.stack-frame--jdk {
  color: var(--ts-text-muted);
}

.stack-frame--jdk .frame-class { color: var(--ts-text-muted); }
.stack-frame--jdk .frame-method { color: var(--ts-text-muted); font-weight: 400; }
.stack-frame--jdk .frame-source { color: var(--ts-text-muted); }

.frame-at {
  color: var(--ts-text-muted);
}

.frame-class {
  color: var(--ts-text-secondary);
}

.frame-method {
  color: #2563eb;
  font-weight: 500;
}

.frame-source {
  color: #16a34a;
}

/* ── Lock Actions (inline with stack frames) ── */
.lock-action {
  font-size: 12px;
  padding: 3px 8px 3px 20px;
  margin: 1px 0;
  color: var(--ts-text-secondary);
  line-height: 1.7;
  cursor: pointer;
  border-radius: var(--ts-radius-sm);
  transition: background var(--ts-transition);
  display: flex;
  align-items: center;
  gap: 4px;
}

.lock-action:hover {
  background: var(--ts-bg-hover);
}

.lock-action .lock-goto {
  opacity: 0;
  font-size: 11px;
  color: var(--ts-accent);
  margin-left: 4px;
  transition: opacity var(--ts-transition);
}

.lock-action:hover .lock-goto {
  opacity: 1;
}

/* Lock type label colors */
.lock-type {
  font-weight: 500;
}

.lock-action--held .lock-type {
  color: #16a34a;
}

.lock-action--blocked .lock-type {
  color: #dc2626;
}

.lock-action--parking .lock-type {
  color: #d97706;
}

.lock-action--waiting .lock-type {
  color: #d97706;
}

/* Lock address — clickable accent color */
.lock-addr {
  color: var(--ts-accent);
  text-decoration: underline;
  text-decoration-style: dotted;
  text-underline-offset: 2px;
}

.lock-action:hover .lock-addr {
  text-decoration-style: solid;
}

/* Left border indicator for lock action type */
.lock-action--held {
  border-left: 2px solid #16a34a;
}

.lock-action--blocked {
  border-left: 2px solid #dc2626;
  background: #fef2f2;
}

.lock-action--parking {
  border-left: 2px solid #d97706;
}

.lock-action--waiting {
  border-left: 2px solid #d97706;
}

/* ── Ownable Synchronizers ── */
.ownable-sync {
  margin-top: var(--ts-space-sm);
  padding-top: var(--ts-space-sm);
  border-top: 1px solid var(--ts-border-color);
}

.sync-header {
  font-size: var(--ts-font-size-xs);
  color: var(--ts-text-muted);
  margin-bottom: var(--ts-space-xs);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.sync-entry {
  font-size: 12px;
  color: var(--ts-text-secondary);
  padding: 1px 0;
  line-height: 1.6;
}

.empty-state {
  padding: var(--ts-space-2xl);
  text-align: center;
  color: var(--ts-text-muted);
  background: var(--ts-bg-surface);
  border-radius: var(--ts-radius-lg);
  font-size: var(--ts-font-size-sm);
}

/* ── Pagination ── */
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--ts-space-md);
  padding: var(--ts-space-sm);
}

.page-btn {
  padding: 4px 12px;
  border: var(--ts-border);
  border-radius: var(--ts-radius-sm);
  background: var(--ts-bg-surface);
  color: var(--ts-text-secondary);
  cursor: pointer;
  font-size: var(--ts-font-size-sm);
  transition: all var(--ts-transition);
}

.page-btn:hover:not(:disabled) {
  border-color: var(--ts-accent);
  color: var(--ts-accent);
  box-shadow: var(--ts-shadow-xs);
}

.page-btn:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.page-info {
  font-size: var(--ts-font-size-sm);
  color: var(--ts-text-muted);
}
</style>
