<script setup lang="ts">
/**
 * ThreadDetailTab — 单个线程详情 Tab 页。
 * 从外部页面（Dashboard TOP10、ThreadPool 等）跳转到 Threads 时，
 * 以独立 Tab 展示线程完整信息，不影响主列表的搜索和过滤状态。
 */
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAnalysisStore } from '@/stores/analysisStore'
import { STATE_LABELS, type ThreadState, type ThreadInfo, type LockInfo } from '@/types'
import * as api from '@/api/threadscope'
import StackTraceView from '@/components/common/StackTraceView.vue'

/** threadRef: 线程标识 (key) 或线程名 */
const props = defineProps<{ threadRef: string }>()

const store = useAnalysisStore()
const route = useRoute()
const router = useRouter()

const thread = ref<ThreadInfo | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

const lockMap = computed<Record<string, LockInfo>>(() => {
  const map: Record<string, LockInfo> = {}
  for (const lock of store.locks) {
    map[lock.lockAddress] = lock
  }
  return map
})

onMounted(async () => {
  if (store.locks.length === 0) {
    store.loadLocks()
  }
  await loadThread()
})

async function loadThread() {
  if (!store.analysisId) return
  loading.value = true
  error.value = null
  try {
    thread.value = await api.fetchThread(store.analysisId, props.threadRef)
    store.renameThreadDetailTab(props.threadRef, thread.value.name)
  } catch (e: any) {
    error.value = e.message || 'Failed to load thread'
  } finally {
    loading.value = false
  }
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

function getStateBadgeClass(state: ThreadState): string {
  return state === 'BLOCKED' ? 'state-pulse-danger' : ''
}

function navigateToLock(lockAddress: string) {
  store.highlightLockAddress = lockAddress
  router.push({ name: 'locks', params: { analysisId: route.params.analysisId } })
}

function openCarrier(carrierKey: string) {
  store.openThreadDetailTab(carrierKey)
}

const kindLabel: Record<string, string> = { VIRTUAL: '虚拟线程', CARRIER: '载体线程' }
</script>

<template>
  <div class="thread-detail-tab">
    <!-- Loading -->
    <div v-if="loading" class="detail-loading">
      <div class="loading-spinner"></div>
      <span>Loading thread details...</span>
    </div>

    <!-- Error -->
    <div v-else-if="error" class="detail-error">
      <span>{{ error }}</span>
      <button class="retry-btn" @click="loadThread">Retry</button>
    </div>

    <!-- Content -->
    <template v-else-if="thread">
      <!-- Header -->
      <div class="detail-header">
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
        <h3 class="detail-title mono">{{ thread.name }}</h3>
        <span v-if="thread.kind !== 'PLATFORM'" class="kind-tag" :class="'kind-tag--' + thread.kind.toLowerCase()">
          {{ kindLabel[thread.kind] }}
        </span>
        <span v-if="thread.daemon" class="daemon-tag">daemon</span>
        <span v-if="thread.stateInferred" class="inferred-tag" title="jcmd 导出不含线程状态，此状态由栈顶推断">状态推断</span>
        <span
          v-if="thread.stackTrace?.length"
          class="stack-depth-badge mono"
        >
          {{ thread.stackTrace.length }} frames
        </span>
      </div>

      <!-- Meta -->
      <div class="detail-meta">
        <div class="meta-item">
          <span class="meta-label">Thread #</span>
          <span class="meta-value mono">{{ thread.threadNumber }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">Priority</span>
          <span class="meta-value mono">{{ thread.priority }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">OS Priority</span>
          <span class="meta-value mono">{{ thread.osPriority }}</span>
        </div>
        <div v-if="thread.cpuTime" class="meta-item">
          <span class="meta-label">CPU</span>
          <span class="meta-value mono">{{ thread.cpuTime }}</span>
        </div>
        <div v-if="thread.elapsed" class="meta-item">
          <span class="meta-label">Elapsed</span>
          <span class="meta-value mono">{{ thread.elapsed }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">ID</span>
          <span class="meta-value mono accent">{{ thread.key }}</span>
        </div>
        <div v-if="thread.tid && thread.tid !== '0x0'" class="meta-item">
          <span class="meta-label">tid</span>
          <span class="meta-value mono accent">{{ thread.tid }}</span>
        </div>
        <div v-if="thread.nidDecimal > 0" class="meta-item">
          <span class="meta-label">nid</span>
          <span class="meta-value mono accent">{{ thread.nid }}</span>
          <span class="meta-sub mono">({{ thread.nidDecimal }})</span>
        </div>
        <div v-if="thread.carrier" class="meta-item">
          <span class="meta-label">{{ thread.kind === 'CARRIER' ? '承载虚拟线程' : '载体线程' }}</span>
          <span
            class="meta-value mono accent"
            :class="{ 'meta-link': thread.kind === 'VIRTUAL' }"
            @click="thread.kind === 'VIRTUAL' && openCarrier(thread.carrier!)"
          >{{ thread.carrier }}</span>
        </div>
        <div v-if="thread.container" class="meta-item">
          <span class="meta-label">容器</span>
          <span class="meta-value mono">{{ thread.container }}</span>
        </div>
        <div v-if="thread.stateDetail" class="meta-item">
          <span class="meta-label">Detail</span>
          <span class="meta-value">{{ thread.stateDetail }}</span>
        </div>
      </div>

      <!-- Stack Trace -->
      <div v-if="thread.stackTrace?.length" class="stack-section">
        <StackTraceView
          :frames="thread.stackTrace"
          :lock-actions="thread.lockActions"
          :lock-map="lockMap"
          @navigate-lock="navigateToLock"
        />
      </div>

      <!-- Ownable Synchronizers -->
      <div v-if="thread.ownableSynchronizers?.length" class="sync-section">
        <div class="section-header">LOCKED OWNABLE SYNCHRONIZERS</div>
        <div v-for="(sync, idx) in thread.ownableSynchronizers" :key="idx" class="sync-entry mono">
          {{ sync }}
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.thread-detail-tab {
  display: flex;
  flex-direction: column;
  gap: var(--ts-space-md);
  height: 100%;
  min-height: 0;
  overflow: hidden;
  padding: var(--ts-space-md);
}

/* ── Loading / Error ── */
.detail-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--ts-space-sm);
  padding: var(--ts-space-2xl);
  color: var(--ts-text-muted);
  font-size: var(--ts-font-size-sm);
}

.loading-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid var(--ts-border-color);
  border-top-color: var(--ts-accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

.detail-error {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--ts-space-md);
  padding: var(--ts-space-2xl);
  color: var(--ts-danger);
  font-size: var(--ts-font-size-sm);
}

.retry-btn {
  padding: 4px 12px;
  border: 1px solid var(--ts-accent);
  border-radius: var(--ts-radius-sm);
  background: transparent;
  color: var(--ts-accent);
  cursor: pointer;
  font-size: var(--ts-font-size-xs);
  transition: all var(--ts-transition);
}

.retry-btn:hover {
  background: var(--ts-accent-light);
}

/* ── Header ── */
.detail-header {
  display: flex;
  align-items: center;
  gap: var(--ts-space-sm);
  padding: var(--ts-space-md);
  background: var(--ts-bg-surface);
  border: var(--ts-border);
  border-radius: var(--ts-radius-lg);
}

.detail-title {
  font-size: var(--ts-font-size-md);
  font-weight: 600;
  color: var(--ts-text-primary);
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.state-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: var(--ts-radius-full);
  flex-shrink: 0;
  letter-spacing: 0.3px;
  line-height: 1.45;
}

.daemon-tag {
  font-size: 10px;
  padding: 1px 6px;
  background: var(--ts-bg-inset);
  border-radius: var(--ts-radius-full);
  color: var(--ts-text-muted);
  flex-shrink: 0;
}

.stack-depth-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 10px;
  padding: 1px 8px;
  background: var(--ts-accent-light);
  border: 1px solid #dbeafe;
  border-radius: var(--ts-radius-full);
  color: var(--ts-accent);
  flex-shrink: 0;
  margin-left: auto;
}

/* ── Meta ── */
.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ts-space-sm);
  padding: var(--ts-space-md);
  background: var(--ts-bg-surface);
  border: var(--ts-border);
  border-radius: var(--ts-radius-lg);
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  background: var(--ts-bg-elevated);
  border-radius: var(--ts-radius-sm);
  font-size: var(--ts-font-size-xs);
}

.meta-label {
  color: var(--ts-text-muted);
  font-weight: 600;
}

.meta-value {
  color: var(--ts-text-primary);
}

.meta-value.accent {
  color: var(--ts-accent);
  background: var(--ts-accent-light);
  padding: 0 4px;
  border-radius: var(--ts-radius-xs);
}

.meta-sub {
  color: var(--ts-text-muted);
  font-size: 10px;
}

/* ── Stack Trace ── */
.stack-section {
  background: var(--ts-bg-surface);
  border: var(--ts-border);
  border-radius: var(--ts-radius-lg);
  overflow: hidden;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: var(--ts-space-sm) var(--ts-space-md);
  font-size: var(--ts-font-size-xs);
  color: var(--ts-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  font-weight: 600;
  background: var(--ts-bg-elevated);
  border-bottom: var(--ts-border);
}

.kind-tag {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 8px;
  border-radius: var(--ts-radius-full);
  flex-shrink: 0;
}
.kind-tag--virtual { color: #7c3aed; background: #f5f3ff; border: 1px solid #ddd6fe; }
.kind-tag--carrier { color: #0e7490; background: #ecfeff; border: 1px solid #a5f3fc; }

.inferred-tag {
  font-size: 10px;
  color: #b45309;
  background: #fffbeb;
  border: 1px dashed #fcd34d;
  border-radius: var(--ts-radius-full);
  padding: 0 6px;
  cursor: help;
  flex-shrink: 0;
}

.meta-link {
  cursor: pointer;
  text-decoration: underline;
  text-decoration-style: dotted;
}

/* ── Ownable Synchronizers ── */
.sync-section {
  background: var(--ts-bg-surface);
  border: var(--ts-border);
  border-radius: var(--ts-radius-lg);
  overflow: hidden;
}

.sync-entry {
  font-size: 12px;
  color: var(--ts-text-secondary);
  padding: 2px var(--ts-space-md);
  line-height: 1.6;
}

.sync-entry:last-child {
  padding-bottom: var(--ts-space-sm);
}
</style>
