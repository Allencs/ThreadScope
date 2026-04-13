import axios from 'axios'
import type {
  UploadResponse,
  OverviewData,
  ThreadListResponse,
  ThreadInfo,
  ThreadSummary,
  LockInfo,
  DeadlockInfo,
  ThreadPoolInfo,
  StackAggregateGroup,
  MethodHotspot,
} from '@/types'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
})

// ── Upload ──
export async function uploadDump(file: File): Promise<UploadResponse> {
  const formData = new FormData()
  formData.append('file', file)
  const { data } = await api.post<UploadResponse>('/dump/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return data
}

export async function pasteDump(content: string): Promise<UploadResponse> {
  const { data } = await api.post<UploadResponse>('/dump/paste', { content })
  return data
}

// ── Overview ──
export async function fetchOverview(analysisId: string): Promise<OverviewData> {
  const { data } = await api.get<OverviewData>(`/analysis/${analysisId}/overview`)
  return data
}

// ── Threads ──
export async function fetchThreads(
  analysisId: string,
  params: {
    state?: string
    search?: string
    nid?: string
    lockAddress?: string
    poolName?: string
    sort?: string
    page?: number
    size?: number
  } = {}
): Promise<ThreadListResponse> {
  const { data } = await api.get<ThreadListResponse>(`/analysis/${analysisId}/threads`, { params })
  return data
}

export async function fetchThread(analysisId: string, threadName: string): Promise<ThreadInfo> {
  const { data } = await api.get<ThreadInfo>(`/analysis/${analysisId}/threads/${encodeURIComponent(threadName)}`)
  return data
}

/** Batch fetch threads by name list — used for lock expansion preloading. */
export async function fetchThreadsBatch(
  analysisId: string,
  names: string[]
): Promise<{ threads: ThreadInfo[]; total: number }> {
  const { data } = await api.post(`/analysis/${analysisId}/threads/batch`, { names })
  return data
}

/** Batch fetch thread summaries (state/daemon/stackDepth only) — lightweight preloading for lock thread lists. */
export async function fetchThreadsBatchSummary(
  analysisId: string,
  names: string[]
): Promise<{ summaries: ThreadSummary[]; total: number }> {
  const { data } = await api.post(`/analysis/${analysisId}/threads/batch-summary`, { names })
  return data
}

// ── Locks ──
export async function fetchLocks(analysisId: string): Promise<{ lockInfos: LockInfo[]; deadlocks: DeadlockInfo }> {
  const { data } = await api.get(`/analysis/${analysisId}/locks`)
  return data
}

// ── Thread Pools ──
export async function fetchThreadPools(analysisId: string): Promise<{ pools: ThreadPoolInfo[] }> {
  const { data } = await api.get(`/analysis/${analysisId}/thread-pools`)
  return data
}

// ── Stack Aggregations ──
export async function fetchStackAggregations(
  analysisId: string,
  minGroupSize = 2
): Promise<{ groups: StackAggregateGroup[]; totalGroups: number }> {
  const { data } = await api.get(`/analysis/${analysisId}/stack-aggregations`, {
    params: { minGroupSize },
  })
  return data
}

// ── Method Hotspots ──
export async function fetchMethodHotspots(
  analysisId: string,
  topN = 20
): Promise<{ hotspots: MethodHotspot[] }> {
  const { data } = await api.get(`/analysis/${analysisId}/method-hotspots`, {
    params: { topN },
  })
  return data
}
