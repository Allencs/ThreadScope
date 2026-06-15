import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type {
  OverviewData,
  ThreadInfo,
  LockInfo,
  DeadlockInfo,
  ThreadPoolInfo,
  StackAggregateGroup,
  MethodHotspot,
  ThreadState,
} from '@/types'
import * as api from '@/api/threadscope'

export const useAnalysisStore = defineStore('analysis', () => {
  // ── Core State ──
  const analysisId = ref<string | null>(null)
  const fileName = ref('')
  const loading = ref(false)
  const error = ref<string | null>(null)

  // ── Module Data (Lazy Loaded) ──
  const overview = ref<OverviewData | null>(null)
  const threads = ref<ThreadInfo[]>([])
  const threadTotal = ref(0)
  const locks = ref<LockInfo[]>([])
  const deadlocks = ref<DeadlockInfo | null>(null)
  const threadPools = ref<ThreadPoolInfo[]>([])
  const stackAggregations = ref<StackAggregateGroup[]>([])
  const methodHotspots = ref<MethodHotspot[]>([])

  // ── UI State ──
  const activeModule = ref<string>('dashboard')
  const searchQuery = ref('')
  const stateFilter = ref<ThreadState[]>([])
  const sortBy = ref<string>('default')
  const highlightLockAddress = ref<string | null>(null)

  // ── Thread Pool Cache ──
  // 缓存每个线程池展开后的线程列表，避免每次切换/刷新页面都重新请求。
  // 通过 sessionStorage 按 analysisId 持久化，刷新页面（同一标签页）后仍命中缓存。
  const threadPoolsLoaded = ref(false)
  const poolThreads = ref<Record<string, ThreadInfo[]>>({})
  const poolThreadsLoading = ref<Record<string, boolean>>({})
  const expandedPools = ref<string[]>([])

  function poolCacheStorageKey(id: string): string {
    return `ts:poolCache:${id}`
  }

  function persistPoolCache() {
    if (!analysisId.value) return
    try {
      sessionStorage.setItem(
        poolCacheStorageKey(analysisId.value),
        JSON.stringify({
          pools: threadPools.value,
          threads: poolThreads.value,
          expanded: expandedPools.value,
        }),
      )
    } catch {
      // 忽略持久化失败（如隐私模式 / 配额超限），不影响内存缓存。
    }
  }

  function hydratePoolCache(id: string): boolean {
    try {
      const raw = sessionStorage.getItem(poolCacheStorageKey(id))
      if (!raw) return false
      const data = JSON.parse(raw) as {
        pools?: ThreadPoolInfo[]
        threads?: Record<string, ThreadInfo[]>
        expanded?: string[]
      }
      if (!Array.isArray(data.pools)) return false
      threadPools.value = data.pools
      poolThreads.value = data.threads ?? {}
      expandedPools.value = data.expanded ?? []
      threadPoolsLoaded.value = true
      return true
    } catch {
      return false
    }
  }

  function clearPoolCache() {
    if (analysisId.value) {
      try {
        sessionStorage.removeItem(poolCacheStorageKey(analysisId.value))
      } catch {
        // ignore
      }
    }
    threadPoolsLoaded.value = false
    poolThreads.value = {}
    poolThreadsLoading.value = {}
    expandedPools.value = []
  }

  /** 懒加载并缓存指定线程池内的线程列表。 */
  async function loadPoolThreads(poolName: string) {
    if (!analysisId.value) return
    if (poolThreads.value[poolName]) return // 命中缓存
    poolThreadsLoading.value[poolName] = true
    try {
      const res = await api.fetchThreads(analysisId.value, {
        poolName,
        page: 1,
        size: 500, // 线程池内线程数通常有界
      })
      poolThreads.value[poolName] = res.threads
      persistPoolCache()
    } catch {
      poolThreads.value[poolName] = []
    } finally {
      poolThreadsLoading.value[poolName] = false
    }
  }

  function togglePoolExpanded(poolName: string) {
    const idx = expandedPools.value.indexOf(poolName)
    if (idx >= 0) {
      expandedPools.value.splice(idx, 1)
    } else {
      expandedPools.value.push(poolName)
    }
    persistPoolCache()
  }

  // ── Thread Detail Tabs ──
  const threadDetailTabs = ref<string[]>([])
  const activeThreadTab = ref<string | null>(null)

  function openThreadDetailTab(threadName: string) {
    if (!threadDetailTabs.value.includes(threadName)) {
      threadDetailTabs.value.push(threadName)
    }
    activeThreadTab.value = threadName
  }

  function closeThreadDetailTab(threadName: string) {
    const idx = threadDetailTabs.value.indexOf(threadName)
    if (idx >= 0) {
      threadDetailTabs.value.splice(idx, 1)
    }
    if (activeThreadTab.value === threadName) {
      activeThreadTab.value = null
    }
  }

  function switchToThreadList() {
    activeThreadTab.value = null
  }

  // ── Computed ──
  const isAnalyzed = computed(() => analysisId.value !== null)
  const healthLevel = computed(() => overview.value?.healthReport?.overallLevel ?? 'HEALTHY')

  // ── Actions ──
  async function uploadFile(file: File) {
    loading.value = true
    error.value = null
    try {
      const res = await api.uploadDump(file)
      analysisId.value = res.analysisId
      fileName.value = res.fileName
      await loadOverview()
    } catch (e: any) {
      error.value = e.message || 'Upload failed'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function pasteContent(content: string) {
    loading.value = true
    error.value = null
    try {
      const res = await api.pasteDump(content)
      analysisId.value = res.analysisId
      fileName.value = res.fileName
      await loadOverview()
    } catch (e: any) {
      error.value = e.message || 'Paste analysis failed'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function loadOverview() {
    if (!analysisId.value) return
    overview.value = await api.fetchOverview(analysisId.value)
  }

  async function loadThreads(page = 1, size = 50) {
    if (!analysisId.value) return
    const res = await api.fetchThreads(analysisId.value, {
      state: stateFilter.value.length > 0 ? stateFilter.value.join(',') : undefined,
      search: searchQuery.value || undefined,
      sort: sortBy.value !== 'default' ? sortBy.value : undefined,
      page,
      size,
    })
    threads.value = res.threads
    threadTotal.value = res.total
  }

  async function loadLocks() {
    if (!analysisId.value) return
    const res = await api.fetchLocks(analysisId.value)
    locks.value = res.lockInfos
    deadlocks.value = res.deadlocks
  }

  async function loadThreadPools(force = false) {
    if (!analysisId.value) return
    // 内存缓存命中：从别的页面切回时直接复用，无需请求。
    if (!force && threadPoolsLoaded.value) return
    // sessionStorage 缓存命中：刷新页面后复用，无需请求。
    if (!force && hydratePoolCache(analysisId.value)) return
    const res = await api.fetchThreadPools(analysisId.value)
    threadPools.value = res.pools
    threadPoolsLoaded.value = true
    persistPoolCache()
  }

  async function loadStackAggregations(minGroupSize = 2) {
    if (!analysisId.value) return
    const res = await api.fetchStackAggregations(analysisId.value, minGroupSize)
    stackAggregations.value = res.groups
  }

  async function loadMethodHotspots(topN = 20) {
    if (!analysisId.value) return
    const res = await api.fetchMethodHotspots(analysisId.value, topN)
    methodHotspots.value = res.hotspots
  }

  function reset() {
    clearPoolCache()
    analysisId.value = null
    fileName.value = ''
    overview.value = null
    threads.value = []
    threadTotal.value = 0
    locks.value = []
    deadlocks.value = null
    threadPools.value = []
    stackAggregations.value = []
    methodHotspots.value = []
    error.value = null
    activeModule.value = 'dashboard'
    searchQuery.value = ''
    stateFilter.value = []
    sortBy.value = 'default'
    highlightLockAddress.value = null
    threadDetailTabs.value = []
    activeThreadTab.value = null
  }

  return {
    // State
    analysisId, fileName, loading, error,
    overview, threads, threadTotal,
    locks, deadlocks, threadPools, stackAggregations, methodHotspots,
    activeModule, searchQuery, stateFilter, sortBy, highlightLockAddress,
    threadDetailTabs, activeThreadTab,
    openThreadDetailTab, closeThreadDetailTab, switchToThreadList,
    // Pool cache
    threadPoolsLoaded, poolThreads, poolThreadsLoading, expandedPools,
    loadPoolThreads, togglePoolExpanded, clearPoolCache,
    // Computed
    isAnalyzed, healthLevel,
    // Actions
    uploadFile, pasteContent,
    loadOverview, loadThreads, loadLocks,
    loadThreadPools, loadStackAggregations, loadMethodHotspots,
    reset,
  }
})
