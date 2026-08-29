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
  DumpComparison,
  CallTreeNode,
  CorrelationResult,
} from '@/types'

/** 后端统一错误体（GlobalExceptionHandler.ErrorResponse） */
interface ApiErrorBody {
  code?: string
  message?: string
}

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
})

/** 上传/粘贴的解析耗时更长，与 nginx proxy_read_timeout (120s) 对齐 */
const UPLOAD_TIMEOUT_MS = 120000

/**
 * 统一错误转换：优先取后端错误体的 message，
 * 否则将超时/网络错误映射为可读文案（而不是 "timeout of 30000ms exceeded"）。
 */
function toFriendlyError(error: unknown): Error {
  if (axios.isAxiosError<ApiErrorBody>(error)) {
    if (error.response?.data?.message) return new Error(error.response.data.message)
    if (error.code === 'ECONNABORTED') {
      return new Error('Request timed out — the dump may be too large or the server is busy')
    }
    if (error.response) {
      if (error.response.status === 413) return new Error('Dump exceeds the 50MB size limit')
      return new Error(`Request failed (HTTP ${error.response.status})`)
    }
    return new Error('Network error — unable to reach the server')
  }
  return error instanceof Error ? error : new Error(String(error))
}

api.interceptors.response.use(undefined, (error) => Promise.reject(toFriendlyError(error)))

// ── Upload ──
/**
 * 上传一个或多个 dump 文件。
 * 多个文件按文件名排序视为按时间先后抓取的快照，后端自动做差分对比。
 */
export async function uploadDump(
  files: File[],
  onProgress?: (percent: number) => void
): Promise<UploadResponse> {
  const formData = new FormData()
  for (const file of files) {
    formData.append('file', file)
  }
  // 不手动设置 Content-Type：交给浏览器生成带 boundary 的 multipart 头
  const { data } = await api.post<UploadResponse>('/dump/upload', formData, {
    timeout: UPLOAD_TIMEOUT_MS,
    onUploadProgress: (e) => {
      if (onProgress && e.total) {
        onProgress(Math.round((e.loaded / e.total) * 100))
      }
    },
  })
  return data
}

export async function pasteDump(content: string): Promise<UploadResponse> {
  const { data } = await api.post<UploadResponse>(
    '/dump/paste',
    { content },
    { timeout: UPLOAD_TIMEOUT_MS }
  )
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

// ── 多 Dump 对比 ──
export async function fetchComparison(analysisId: string): Promise<DumpComparison | null> {
  const res = await api.get<DumpComparison>(`/analysis/${analysisId}/comparison`)
  // 单 dump 分析返回 204 No Content
  return res.status === 204 ? null : res.data
}

// ── 调用树 (火焰图) ──
export async function fetchCallTree(analysisId: string, state?: string): Promise<CallTreeNode> {
  const { data } = await api.get<CallTreeNode>(`/analysis/${analysisId}/calltree`, {
    params: state ? { state } : {},
  })
  return data
}

// ── top -H CPU 关联 ──
export async function correlateCpu(analysisId: string, topOutput: string): Promise<CorrelationResult> {
  const { data } = await api.post<CorrelationResult>(
    `/analysis/${analysisId}/cpu-correlation`,
    { topOutput }
  )
  return data
}
