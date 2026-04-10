<script setup lang="ts">
/**
 * LockAnalyzer — 锁分析与死锁检测视图。
 * - 每行锁可展开查看等待线程列表
 * - 每个等待线程可再次展开，显示完整堆栈帧和锁操作（与 Threads Explorer 一致）
 * - 支持从 Dashboard 跳转高亮
 */
import { onMounted, ref, watch, nextTick, computed, reactive } from 'vue'
import { useAnalysisStore } from '@/stores/analysisStore'
import { STATE_COLORS, STATE_LABELS } from '@/types'
import type { ThreadInfo, ThreadState, LockAction } from '@/types'
import * as api from '@/api/threadscope'

const store = useAnalysisStore()

// ── Lock level expand state ──
const expandedLocks = ref<Set<string>>(new Set())
const lockRefs = ref<Record<string, HTMLElement | null>>({})
const searchQuery = ref('')

// ── Thread level expand state ──
const expandedThreads = ref<Set<string>>(new Set())
const threadCache = reactive<Record<string, ThreadInfo>>({})
const threadLoading = ref<Set<string>>(new Set())

onMounted(async () => {
  await store.loadLocks()
  if (store.highlightLockAddress) {
    await nextTick()
    expandAndScrollTo(store.highlightLockAddress)
  }
})

watch(() => store.highlightLockAddress, (addr) => {
  if (addr) expandAndScrollTo(addr)
})

// ── Filtered locks ──
const filteredLocks = computed(() => {
  if (!searchQuery.value) return store.locks
  const q = searchQuery.value.toLowerCase()
  return store.locks.filter(lock =>
    lock.lockAddress.toLowerCase().includes(q) ||
    lock.lockClassName.toLowerCase().includes(q) ||
    (lock.holderThreadName?.toLowerCase().includes(q) ?? false) ||
    lock.waitingThreadNames.some(n => n.toLowerCase().includes(q))
  )
})

// ── Lock expand/collapse ──
function toggleLock(addr: string) {
  const s = new Set(expandedLocks.value)
  if (s.has(addr)) {
    s.delete(addr)
  } else {
    s.add(addr)
    // Pre-load thread info for all waiting threads of this lock
    const lock = store.locks.find(l => l.lockAddress === addr)
    if (lock) {
      preloadThreads(lock.waitingThreadNames, lock.holderThreadName)
    }
  }
  expandedLocks.value = s
}

function isLockExpanded(addr: string): boolean {
  return expandedLocks.value.has(addr)
}

function isHighlighted(addr: string): boolean {
  return store.highlightLockAddress === addr
}

function expandAndScrollTo(addr: string) {
  const s = new Set(expandedLocks.value)
  s.add(addr)
  expandedLocks.value = s

  // Pre-load thread info for the highlighted lock
  const lock = store.locks.find(l => l.lockAddress === addr)
  if (lock) {
    preloadThreads(lock.waitingThreadNames, lock.holderThreadName)
  }

  nextTick(() => {
    const el = lockRefs.value[addr]
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' })
      setTimeout(() => { store.highlightLockAddress = null }, 3000)
    }
  })
}

function setLockRef(addr: string, el: any) {
  if (el) lockRefs.value[addr] = el
}

// ── Thread expand/collapse (nested inside lock) ──
function threadKey(lockAddr: string, threadName: string): string {
  return lockAddr + '::' + threadName
}

function toggleThread(lockAddr: string, threadName: string) {
  const key = threadKey(lockAddr, threadName)
  const s = new Set(expandedThreads.value)
  if (s.has(key)) {
    s.delete(key)
  } else {
    s.add(key)
    // Lazy load thread info
    if (!threadCache[threadName]) {
      loadThreadInfo(threadName)
    }
  }
  expandedThreads.value = s
}

function isThreadExpanded(lockAddr: string, threadName: string): boolean {
  return expandedThreads.value.has(threadKey(lockAddr, threadName))
}

async function loadThreadInfo(threadName: string) {
  if (threadCache[threadName] || threadLoading.value.has(threadName)) return
  const l = new Set(threadLoading.value)
  l.add(threadName)
  threadLoading.value = l
  try {
    if (store.analysisId) {
      threadCache[threadName] = await api.fetchThread(store.analysisId, threadName)
    }
  } catch (e) {
    console.error(`Failed to load thread: ${threadName}`, e)
  } finally {
    const l2 = new Set(threadLoading.value)
    l2.delete(threadName)
    threadLoading.value = l2
  }
}

/**
 * Batch pre-load thread info for all waiting threads + holder of a lock.
 * Uses the batch API to fetch all at once instead of N individual requests.
 */
async function preloadThreads(waitingNames: string[], holderName: string | null) {
  // Collect names that are not yet cached
  const allNames = [...waitingNames]
  if (holderName) allNames.push(holderName)
  const uncached = allNames.filter(n => !threadCache[n])
  if (uncached.length === 0 || !store.analysisId) return

  // Mark all as loading
  const loadingSet = new Set(threadLoading.value)
  uncached.forEach(n => loadingSet.add(n))
  threadLoading.value = loadingSet

  try {
    const res = await api.fetchThreadsBatch(store.analysisId, uncached)
    for (const t of res.threads) {
      threadCache[t.name] = t
    }
  } catch (e) {
    console.error('Failed to batch load threads', e)
  } finally {
    const doneSet = new Set(threadLoading.value)
    uncached.forEach(n => doneSet.delete(n))
    threadLoading.value = doneSet
  }
}

// ── Helpers ──
function severityColor(count: number): string {
  if (count >= 50) return 'var(--ts-danger)'
  if (count >= 10) return 'var(--ts-warning)'
  if (count >= 5) return '#d29922'
  return 'var(--ts-text-secondary)'
}

function shortClassName(full: string): string {
  const parts = full.split('.')
  return parts[parts.length - 1] || full
}

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

function getStateBadgeClass(state: ThreadState): string {
  const map: Record<string, string> = {
    BLOCKED: 'state-pulse-danger',
    RUNNABLE: '',
    WAITING: '',
    TIMED_WAITING: '',
  }
  return map[state] || ''
}
</script>

<template>
  <div class="lock-analyzer">
    <!-- ═══════ Deadlock Alert ═══════ -->
    <div v-if="store.deadlocks?.chains?.length" class="deadlock-alert animate-card-enter">
      <div class="alert-header">
        <div class="alert-icon-wrap">
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
            <path d="M10 2L18 17H2L10 2Z" stroke="#dc2626" stroke-width="1.5" fill="#fef2f2"/>
            <path d="M10 8v4M10 14h.01" stroke="#dc2626" stroke-width="1.5" stroke-linecap="round"/>
          </svg>
        </div>
        <h3>{{ store.deadlocks.chains.length }} Deadlock{{ store.deadlocks.chains.length > 1 ? 's' : '' }} Detected</h3>
      </div>
      <div v-for="(chain, idx) in store.deadlocks.chains" :key="idx" class="deadlock-chain">
        <div class="chain-threads">
          <span v-for="(name, tidx) in chain.threadNames" :key="tidx" class="chain-thread mono">
            {{ name }}
            <span v-if="tidx < chain.threadNames.length - 1" class="chain-arrow">→</span>
          </span>
          <span class="chain-arrow">⟲</span>
        </div>
        <p v-if="chain.description" class="chain-desc">{{ chain.description }}</p>
      </div>
    </div>

    <!-- ═══════ Lock Contention Panel ═══════ -->
    <div class="panel animate-card-enter">
      <div class="panel-header">
        <div class="panel-title-group">
          <svg class="panel-icon" width="16" height="16" viewBox="0 0 16 16" fill="none">
            <rect x="4" y="7" width="8" height="7" rx="1.5" stroke="var(--ts-text-secondary)" stroke-width="1.3"/>
            <path d="M6 7V5a2 2 0 114 0v2" stroke="var(--ts-text-secondary)" stroke-width="1.3" stroke-linecap="round"/>
          </svg>
          <h3 class="panel-title">Lock Contention</h3>
          <span class="panel-count">{{ store.locks.length }}</span>
        </div>
        <div class="search-box">
          <svg class="search-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>
          </svg>
          <input v-model="searchQuery" type="text" placeholder="Search locks, threads..." class="search-input" />
        </div>
      </div>

      <div class="lock-table">
        <!-- Table Header -->
        <div class="lock-table-header">
          <span class="col-expand"></span>
          <span class="col-addr">Lock Address</span>
          <span class="col-class">Class</span>
          <span class="col-holder">Holder</span>
          <span class="col-waiters">Waiters</span>
          <span class="col-severity">Severity</span>
        </div>

        <!-- Lock Rows -->
        <template v-for="lock in filteredLocks" :key="lock.lockAddress">
          <!-- Lock Row Header -->
          <div
            :ref="(el) => setLockRef(lock.lockAddress, el)"
            class="lock-row"
            :class="{
              'lock-row--expanded': isLockExpanded(lock.lockAddress),
              'lock-row--highlighted': isHighlighted(lock.lockAddress),
            }"
            @click="toggleLock(lock.lockAddress)"
          >
            <span class="col-expand">
              <svg class="expand-arrow" :class="{ 'expand-arrow--open': isLockExpanded(lock.lockAddress) }"
                   width="12" height="12" viewBox="0 0 12 12">
                <path d="M4 2 L8 6 L4 10" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
            </span>
            <span class="col-addr mono">{{ lock.lockAddress }}</span>
            <span class="col-class" :title="lock.lockClassName">{{ shortClassName(lock.lockClassName) }}</span>
            <span class="col-holder mono">
              <span v-if="lock.holderThreadName" class="holder-name">{{ lock.holderThreadName }}</span>
              <span v-else class="holder-unknown">(unknown)</span>
            </span>
            <span class="col-waiters">
              <span class="waiter-count" :style="{ color: severityColor(lock.waitingThreadNames.length) }">
                {{ lock.waitingThreadNames.length }}
              </span>
            </span>
            <span class="col-severity">
              <span class="severity-bar-track">
                <span
                  class="severity-bar-fill"
                  :style="{
                    width: Math.min(100, lock.waitingThreadNames.length * 2) + '%',
                    background: severityColor(lock.waitingThreadNames.length),
                  }"
                ></span>
              </span>
            </span>
          </div>

          <!-- ═══════ Lock Expanded Detail ═══════ -->
          <Transition name="expand">
            <div v-if="isLockExpanded(lock.lockAddress)" class="lock-detail">
              <!-- Lock Meta -->
              <div class="lock-meta-section">
                <div class="lock-meta-grid">
                  <div class="meta-item">
                    <span class="meta-label">Address</span>
                    <span class="meta-value mono">{{ lock.lockAddress }}</span>
                  </div>
                  <div class="meta-item">
                    <span class="meta-label">Class</span>
                    <span class="meta-value mono">{{ lock.lockClassName }}</span>
                  </div>
                  <div class="meta-item">
                    <span class="meta-label">Holder</span>
                    <span class="meta-value mono" :class="lock.holderThreadName ? 'meta-value--holder' : 'meta-value--no-holder'">
                      {{ lock.holderThreadName || '(no holder — Condition/FutureTask wait)' }}
                    </span>
                  </div>
                  <div class="meta-item">
                    <span class="meta-label">Contention</span>
                    <span class="meta-value">
                      <span class="waiter-count-large" :style="{ color: severityColor(lock.waitingThreadNames.length) }">{{ lock.waitingThreadNames.length }}</span>
                      <span class="meta-value-suffix">threads waiting</span>
                    </span>
                  </div>
                </div>
              </div>

              <!-- ═══════ Waiting Thread List ═══════ -->
              <div class="waiter-section">
                <div class="waiter-section-header">
                  <span class="section-label">Waiting Threads ({{ lock.waitingThreadNames.length }})</span>
                  <span class="section-hint">Click to expand stack trace</span>
                </div>

                <div class="thread-list">
                  <div
                    v-for="tName in lock.waitingThreadNames"
                    :key="tName"
                    class="thread-row"
                    :class="{ 'thread-row--expanded': isThreadExpanded(lock.lockAddress, tName) }"
                  >
                    <!-- ── Thread Row Header (matches ThreadExplorer exactly) ── -->
                    <div class="thread-row-header" @click.stop="toggleThread(lock.lockAddress, tName)">
                      <!-- Expand arrow -->
                      <span class="expand-icon">
                        <svg class="expand-arrow" :class="{ 'expand-arrow--open': isThreadExpanded(lock.lockAddress, tName) }"
                             width="10" height="10" viewBox="0 0 12 12">
                          <path d="M4 2 L8 6 L4 10" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                        </svg>
                      </span>

                      <!-- State badge (pill, colored text on subtle bg) -->
                      <span
                        v-if="threadCache[tName]"
                        class="state-badge"
                        :class="getStateBadgeClass(threadCache[tName].state)"
                        :style="{
                          background: (STATE_COLORS[threadCache[tName].state] || '#9ca3af') + '18',
                          color: STATE_COLORS[threadCache[tName].state] || '#9ca3af',
                          borderColor: (STATE_COLORS[threadCache[tName].state] || '#9ca3af') + '30',
                        }"
                      >
                        {{ STATE_LABELS[threadCache[tName].state] || threadCache[tName].state }}
                      </span>
                      <span v-else-if="!threadLoading.has(tName)" class="state-badge state-badge--pending">
                        ...
                      </span>

                      <!-- Thread name -->
                      <span class="thread-name mono">{{ tName }}</span>

                      <!-- Stack depth badge: "N frames" -->
                      <span
                        v-if="threadCache[tName]?.stackTrace?.length"
                        class="frames-badge mono"
                      >
                        <svg class="frames-icon" width="10" height="10" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2">
                          <line x1="2" y1="4" x2="14" y2="4"/><line x1="2" y1="8" x2="14" y2="8"/><line x1="2" y1="12" x2="10" y2="12"/>
                        </svg>
                        {{ threadCache[tName].stackTrace.length }} frames
                      </span>

                      <!-- Daemon tag -->
                      <span v-if="threadCache[tName]?.daemon" class="daemon-tag">daemon</span>

                      <!-- nid -->
                      <span
                        v-if="threadCache[tName]"
                        class="thread-nid mono"
                        :title="'nid=' + threadCache[tName].nid + ' (decimal: ' + threadCache[tName].nidDecimal + ')'"
                      >
                        nid={{ threadCache[tName].nid }}
                      </span>

                      <!-- Lock icon -->
                      <span
                        v-if="threadCache[tName]?.lockActions?.some((a: any) => a.lockAddress)"
                        class="lock-icon"
                        title="Has lock activity"
                      >
                        <svg width="12" height="12" viewBox="0 0 16 16" fill="none">
                          <rect x="4" y="7" width="8" height="7" rx="1.5" stroke="var(--ts-text-muted)" stroke-width="1.2"/>
                          <path d="M6 7V5a2 2 0 114 0v2" stroke="var(--ts-text-muted)" stroke-width="1.2" stroke-linecap="round"/>
                        </svg>
                      </span>

                      <!-- Loading indicator -->
                      <span v-if="threadLoading.has(tName)" class="thread-loading">
                        <span class="loading-spinner"></span>
                      </span>
                    </div>

                    <!-- ── Thread Expanded Detail (same layout as ThreadExplorer) ── -->
                    <div v-if="isThreadExpanded(lock.lockAddress, tName) && threadCache[tName]" class="thread-detail thread-expand-enter">
                      <!-- Meta Info -->
                      <div class="detail-meta">
                        <span><b>Thread #:</b> {{ threadCache[tName].threadNumber }}</span>
                        <span><b>Priority:</b> {{ threadCache[tName].priority }}</span>
                        <span><b>OS Priority:</b> {{ threadCache[tName].osPriority }}</span>
                        <span v-if="threadCache[tName].cpuTime"><b>CPU:</b> {{ threadCache[tName].cpuTime }}</span>
                        <span v-if="threadCache[tName].elapsed"><b>Elapsed:</b> {{ threadCache[tName].elapsed }}</span>
                        <span><b>tid:</b> <code>{{ threadCache[tName].tid }}</code></span>
                        <span><b>nid:</b> <code>{{ threadCache[tName].nid }}</code> ({{ threadCache[tName].nidDecimal }})</span>
                        <span v-if="threadCache[tName].stateDetail"><b>Detail:</b> {{ threadCache[tName].stateDetail }}</span>
                      </div>

                      <!-- Stack Trace with inline lock actions -->
                      <div v-if="threadCache[tName].stackTrace?.length" class="stack-trace">
                        <div class="stack-header">Stack Trace ({{ threadCache[tName].stackTrace.length }} frames)</div>

                        <template v-for="(frame, fIdx) in threadCache[tName].stackTrace" :key="fIdx">
                          <div
                            class="stack-frame mono"
                            :class="{ 'stack-frame--jdk': frame.className.startsWith('java.') || frame.className.startsWith('sun.') || frame.className.startsWith('jdk.') }"
                          >
                            <span class="frame-at">at </span>
                            <span class="frame-class">{{ frame.className }}</span>.<span class="frame-method">{{ frame.methodName }}</span>(<span class="frame-source">{{ frame.source }}</span>)
                          </div>

                          <!-- Lock actions associated with this frame -->
                          <div
                            v-for="(la, laIdx) in getLockActionsForFrame(threadCache[tName], fIdx)"
                            :key="'lock-' + fIdx + '-' + laIdx"
                            class="lock-action mono"
                            :class="getLockActionCssClass(la)"
                          >
                            <span class="lock-type">{{ getLockActionLabel(la) }}</span>
                            &lt;<span class="lock-addr">{{ la.lockAddress }}</span>&gt;
                            (a {{ la.lockClassName }})
                          </div>
                        </template>
                      </div>

                      <!-- Ownable Synchronizers -->
                      <div v-if="threadCache[tName].ownableSynchronizers?.length" class="ownable-sync">
                        <div class="sync-header">Locked Ownable Synchronizers:</div>
                        <div v-for="(s, sIdx) in threadCache[tName].ownableSynchronizers" :key="sIdx" class="sync-entry mono">
                          - {{ s }}
                        </div>
                      </div>
                    </div>

                    <!-- Loading placeholder -->
                    <div v-if="isThreadExpanded(lock.lockAddress, tName) && !threadCache[tName] && threadLoading.has(tName)" class="thread-loading-placeholder">
                      <span class="loading-spinner"></span>
                      <span>Loading thread info...</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </Transition>
        </template>

        <!-- Empty states -->
        <div v-if="filteredLocks.length === 0 && store.locks.length > 0" class="empty-state">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="var(--ts-text-muted)" stroke-width="1.5">
            <circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>
          </svg>
          <span>No locks match "<strong>{{ searchQuery }}</strong>"</span>
        </div>
        <div v-if="store.locks.length === 0" class="empty-state">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="var(--ts-success)" stroke-width="1.5">
            <path d="M22 11.08V12a10 10 0 11-5.93-9.14"/>
            <polyline points="22 4 12 14.01 9 11.01"/>
          </svg>
          <span>No lock contention detected.</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   LockAnalyzer — Modern Light Theme
   Consistent with ThreadExplorer thread rows
   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ */

.lock-analyzer {
  display: flex;
  flex-direction: column;
  gap: var(--ts-space-lg);
}

/* ═══════════════════════════════════════════
   Deadlock Alert
   ═══════════════════════════════════════════ */
.deadlock-alert {
  background: var(--ts-danger-light);
  border: 1px solid #fecaca;
  border-radius: var(--ts-radius-lg);
  padding: var(--ts-space-lg);
}

.alert-header {
  display: flex;
  align-items: center;
  gap: var(--ts-space-sm);
  margin-bottom: var(--ts-space-md);
}

.alert-icon-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.alert-header h3 {
  color: var(--ts-danger);
  font-size: var(--ts-font-size-md);
  font-weight: 600;
}

.deadlock-chain {
  padding: var(--ts-space-sm) var(--ts-space-md);
  background: #ffffff;
  border: 1px solid #fecaca;
  border-radius: var(--ts-radius-sm);
  margin-bottom: var(--ts-space-xs);
}

.chain-threads {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--ts-space-xs);
}

.chain-thread {
  color: var(--ts-text-primary);
  font-size: var(--ts-font-size-sm);
  padding: 2px 8px;
  background: var(--ts-bg-elevated);
  border-radius: var(--ts-radius-xs);
}

.chain-arrow {
  color: var(--ts-danger);
  font-weight: 600;
  font-size: var(--ts-font-size-sm);
}

.chain-desc {
  font-size: var(--ts-font-size-sm);
  color: var(--ts-text-secondary);
  margin-top: var(--ts-space-xs);
}

/* ═══════════════════════════════════════════
   Panel
   ═══════════════════════════════════════════ */
.panel {
  background: var(--ts-bg-surface);
  border: 1px solid var(--ts-border-color);
  border-radius: var(--ts-radius-lg);
  box-shadow: var(--ts-shadow-sm);
  overflow: hidden;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--ts-space-md) var(--ts-space-lg);
  border-bottom: 1px solid var(--ts-border-color);
}

.panel-title-group {
  display: flex;
  align-items: center;
  gap: var(--ts-space-sm);
}

.panel-icon {
  flex-shrink: 0;
}

.panel-title {
  font-size: var(--ts-font-size-md);
  font-weight: 600;
  color: var(--ts-text-primary);
}

.panel-count {
  font-size: var(--ts-font-size-xs);
  font-weight: 600;
  color: var(--ts-text-secondary);
  background: var(--ts-bg-elevated);
  padding: 1px 8px;
  border-radius: var(--ts-radius-full);
}

.search-box {
  display: flex;
  align-items: center;
  gap: var(--ts-space-xs);
  padding: 5px 10px;
  background: var(--ts-bg-primary);
  border: 1px solid var(--ts-border-color);
  border-radius: var(--ts-radius-sm);
  color: var(--ts-text-muted);
  transition: border-color var(--ts-transition);
}

.search-box:focus-within {
  border-color: var(--ts-accent);
  box-shadow: var(--ts-shadow-ring);
}

.search-icon {
  flex-shrink: 0;
}

.search-input {
  background: none;
  border: none;
  color: var(--ts-text-primary);
  font-size: var(--ts-font-size-sm);
  outline: none;
  width: 200px;
  font-family: var(--ts-font-ui);
}

.search-input::placeholder {
  color: var(--ts-text-muted);
}

/* ═══════════════════════════════════════════
   Lock Table
   ═══════════════════════════════════════════ */
.lock-table {
  font-size: var(--ts-font-size-sm);
}

.lock-table-header {
  display: grid;
  grid-template-columns: 32px 160px 1fr 220px 70px 100px;
  gap: var(--ts-space-sm);
  padding: var(--ts-space-sm) var(--ts-space-md);
  background: var(--ts-bg-primary);
  border-bottom: 1px solid var(--ts-border-color);
  color: var(--ts-text-muted);
  font-size: var(--ts-font-size-xs);
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.lock-row {
  display: grid;
  grid-template-columns: 32px 160px 1fr 220px 70px 100px;
  gap: var(--ts-space-sm);
  padding: var(--ts-space-sm) var(--ts-space-md);
  border-bottom: 1px solid var(--ts-border-color);
  align-items: center;
  cursor: pointer;
  transition: background var(--ts-transition);
}

.lock-row:hover {
  background: var(--ts-bg-hover);
}

.lock-row--expanded {
  background: var(--ts-accent-light);
  border-bottom-color: transparent;
}

.lock-row--expanded:hover {
  background: var(--ts-accent-light);
}

.lock-row--highlighted {
  animation: highlight-flash 2.5s ease-out;
}

@keyframes highlight-flash {
  0% {
    background: rgba(37, 99, 235, 0.15);
    box-shadow: inset 3px 0 0 var(--ts-accent);
  }
  40% {
    background: rgba(37, 99, 235, 0.08);
    box-shadow: inset 3px 0 0 rgba(37, 99, 235, 0.5);
  }
  100% {
    background: transparent;
    box-shadow: none;
  }
}

.expand-arrow {
  transition: transform 0.2s ease;
  color: var(--ts-text-muted);
}

.expand-arrow--open {
  transform: rotate(90deg);
}

.col-expand {
  display: flex;
  align-items: center;
  justify-content: center;
}

.col-addr {
  font-size: 12px;
  color: var(--ts-text-primary);
}

.col-class {
  color: var(--ts-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.col-holder {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.holder-name {
  color: var(--ts-success);
  font-weight: 500;
}

.holder-unknown {
  color: var(--ts-text-muted);
  font-style: italic;
}

.waiter-count {
  font-weight: 700;
  font-size: var(--ts-font-size-md);
}

.severity-bar-track {
  width: 100%;
  height: 3px;
  background: var(--ts-bg-elevated);
  border-radius: var(--ts-radius-full);
  overflow: hidden;
}

.severity-bar-fill {
  height: 100%;
  border-radius: var(--ts-radius-full);
  transition: width 0.4s ease;
}

/* ═══════════════════════════════════════════
   Lock Detail (Expanded)
   ═══════════════════════════════════════════ */
.lock-detail {
  background: var(--ts-bg-surface);
  border-top: 1px solid var(--ts-border-color);
  border-bottom: 1px solid var(--ts-border-color);
  overflow: hidden;
}

/* Lock meta section */
.lock-meta-section {
  padding: var(--ts-space-md) var(--ts-space-lg);
  background: var(--ts-bg-inset);
  border-bottom: 1px solid var(--ts-border-color);
}

.lock-meta-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--ts-space-md) var(--ts-space-xl);
}

.meta-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.meta-label {
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--ts-text-muted);
  font-weight: 500;
}

.meta-value {
  font-size: var(--ts-font-size-sm);
  color: var(--ts-text-primary);
  word-break: break-all;
}

.meta-value--holder {
  color: var(--ts-success);
}

.meta-value--no-holder {
  color: var(--ts-text-muted);
  font-style: italic;
}

.meta-value-suffix {
  font-size: var(--ts-font-size-sm);
  color: var(--ts-text-secondary);
  margin-left: 4px;
}

.waiter-count-large {
  font-weight: 700;
  font-size: var(--ts-font-size-lg);
}

/* ═══════════════════════════════════════════
   Waiter Thread Section
   ═══════════════════════════════════════════ */
.waiter-section {
  padding: var(--ts-space-md) var(--ts-space-lg);
}

.waiter-section-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: var(--ts-space-sm);
}

.section-label {
  font-size: var(--ts-font-size-xs);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--ts-text-muted);
  font-weight: 600;
}

.section-hint {
  font-size: 11px;
  color: var(--ts-text-faint);
  font-style: italic;
}

/* ═══════════════════════════════════════════
   Thread List (nested inside lock)
   Matches ThreadExplorer exactly
   ═══════════════════════════════════════════ */
.thread-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
  background: var(--ts-border-color);
  border-radius: var(--ts-radius-md);
  border: 1px solid var(--ts-border-color);
  max-height: 600px;
  overflow-y: auto;
}

.thread-row {
  background: var(--ts-bg-secondary);
}

.thread-row:first-child {
  border-radius: var(--ts-radius-md) var(--ts-radius-md) 0 0;
}

.thread-row:last-child {
  border-radius: 0 0 var(--ts-radius-md) var(--ts-radius-md);
}

.thread-row--expanded {
  background: #fafbfd;
}

/* ── Thread Row Header (identical to ThreadExplorer) ── */
.thread-row-header {
  display: flex;
  align-items: center;
  gap: var(--ts-space-sm);
  padding: var(--ts-space-sm) var(--ts-space-md);
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
  display: flex;
  align-items: center;
  justify-content: center;
}

/* State badge — identical to ThreadExplorer */
.state-badge {
  font-size: 10px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: var(--ts-radius-full);
  border: 1px solid;
  flex-shrink: 0;
  letter-spacing: 0.3px;
  white-space: nowrap;
}

.state-badge--pending {
  background: var(--ts-bg-elevated);
  color: var(--ts-text-muted);
  border-color: var(--ts-border-color);
  font-weight: 400;
}

.thread-name {
  font-size: var(--ts-font-size-sm);
  color: var(--ts-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Stack depth badge: "N frames" — matches ThreadExplorer */
.frames-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 10px;
  font-weight: 500;
  padding: 1px 8px 1px 5px;
  border-radius: var(--ts-radius-full);
  background: var(--ts-accent-light);
  color: var(--ts-accent);
  border: 1px solid #dbeafe;
  flex-shrink: 0;
  white-space: nowrap;
  letter-spacing: 0.2px;
  line-height: 1.45;
}

.frames-icon {
  flex-shrink: 0;
  opacity: 0.7;
}

.daemon-tag {
  font-size: 9px;
  padding: 1px 5px;
  background: var(--ts-bg-elevated);
  border-radius: var(--ts-radius-sm);
  color: var(--ts-text-muted);
  flex-shrink: 0;
  border: 1px solid var(--ts-border-color);
}

.thread-nid {
  font-size: var(--ts-font-size-xs);
  color: var(--ts-text-muted);
  flex-shrink: 0;
  margin-left: auto;
}

.lock-icon {
  flex-shrink: 0;
  display: flex;
  align-items: center;
}

.thread-loading {
  flex-shrink: 0;
  display: flex;
  align-items: center;
}

/* ── Thread Detail (Expanded — identical to ThreadExplorer) ── */
.thread-detail {
  padding: 0 var(--ts-space-md) var(--ts-space-md) calc(var(--ts-space-md) + 16px + var(--ts-space-sm));
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

.detail-meta code {
  color: var(--ts-accent);
  font-size: var(--ts-font-size-xs);
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
  font-weight: 500;
}

.stack-frame {
  font-size: 12px;
  padding: 2px 0;
  color: var(--ts-text-primary);
  line-height: 1.6;
}

.stack-frame--jdk {
  color: var(--ts-text-muted);
}

.frame-at { color: var(--ts-text-muted); }
.frame-class { color: var(--ts-text-secondary); }
.frame-method { color: var(--ts-accent); font-weight: 500; }
.frame-source { color: var(--ts-success); }

.lock-action {
  font-size: 12px;
  padding: 3px 8px 3px 20px;
  margin: 1px 0;
  color: var(--ts-text-secondary);
  line-height: 1.7;
  border-radius: var(--ts-radius-sm);
  display: flex;
  align-items: center;
  gap: 4px;
}

.lock-type { font-weight: 500; }

.lock-action--held .lock-type { color: #16a34a; }
.lock-action--blocked .lock-type { color: #dc2626; }
.lock-action--parking .lock-type { color: #d97706; }
.lock-action--waiting .lock-type { color: #d97706; }

.lock-addr {
  color: var(--ts-accent);
  text-decoration: underline;
  text-decoration-style: dotted;
  text-underline-offset: 2px;
}

.lock-action--held { border-left: 2px solid #16a34a; }
.lock-action--blocked { border-left: 2px solid #dc2626; background: #fef2f2; }
.lock-action--parking { border-left: 2px solid #d97706; }
.lock-action--waiting { border-left: 2px solid #d97706; }

.ownable-sync {
  margin-top: var(--ts-space-sm);
  padding-top: var(--ts-space-sm);
  border-top: 1px solid var(--ts-border-color);
}

.sync-header {
  font-size: var(--ts-font-size-xs);
  color: var(--ts-text-muted);
  margin-bottom: var(--ts-space-xs);
}

.sync-entry {
  font-size: 12px;
  color: var(--ts-text-secondary);
  padding: 1px 0;
}

/* ── Thread Loading Placeholder ── */
.thread-loading-placeholder {
  padding: var(--ts-space-md) var(--ts-space-lg);
  font-size: var(--ts-font-size-sm);
  color: var(--ts-text-muted);
  display: flex;
  align-items: center;
  gap: var(--ts-space-sm);
}

.loading-spinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid var(--ts-border-color);
  border-top-color: var(--ts-accent);
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
  flex-shrink: 0;
}

@keyframes spin { to { transform: rotate(360deg); } }

/* ═══════════════════════════════════════════
   Transitions
   ═══════════════════════════════════════════ */
.expand-enter-active {
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
  max-height: 2000px;
}

.expand-leave-active {
  transition: all 0.2s ease;
  max-height: 2000px;
}

.expand-enter-from,
.expand-leave-to {
  opacity: 0;
  max-height: 0;
}

/* ═══════════════════════════════════════════
   Empty States
   ═══════════════════════════════════════════ */
.empty-state {
  padding: var(--ts-space-2xl);
  text-align: center;
  color: var(--ts-text-muted);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--ts-space-sm);
  font-size: var(--ts-font-size-sm);
}

.empty-state strong {
  color: var(--ts-text-secondary);
}
</style>
