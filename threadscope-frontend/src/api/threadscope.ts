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
  LockGraphView,
  CodeLayerConfig,
  UploadOptions,
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
  onProgress?: (percent: number) => void,
  options: UploadOptions = {},
): Promise<UploadResponse> {
  const formData = new FormData()
  for (const file of files) {
    formData.append('file', file)
  }
  if (options.businessPackages?.length) {
    formData.append('businessPackages', options.businessPackages.join(','))
  }
  if (options.applicationName) {
    formData.append('applicationName', options.applicationName)
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

export async function pasteDump(content: string, options: UploadOptions = {}): Promise<UploadResponse> {
  const { data } = await api.post<UploadResponse>(
    '/dump/paste',
    {
      content,
      businessPackages: options.businessPackages ?? [],
      applicationName: options.applicationName ?? null,
    },
    { timeout: UPLOAD_TIMEOUT_MS }
  )
  return data
}

// ── Config ──
export async function fetchCodeLayerConfig(): Promise<CodeLayerConfig> {
  const { data } = await api.get<CodeLayerConfig>('/config/code-layers')
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
    kind?: string
    sort?: string
    page?: number
    size?: number
  } = {}
): Promise<ThreadListResponse> {
  const { data } = await api.get<ThreadListResponse>(`/analysis/${analysisId}/threads`, { params })
  return data
}

/** 按线程标识 (key) 或线程名查询 — 优先匹配 key，线程名可能重名 */
export async function fetchThread(analysisId: string, ref: string): Promise<ThreadInfo> {
  const { data } = await api.get<ThreadInfo>(`/analysis/${analysisId}/thread`, { params: { ref } })
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

/** 按地址查锁，包括没有等待者的持有锁。找不到返回 null。 */
export async function fetchLockByAddress(analysisId: string, address: string): Promise<LockInfo | null> {
  try {
    const { data } = await api.get<LockInfo>(`/analysis/${analysisId}/locks/by-address`, { params: { address } })
    return data
  } catch (error) {
    if (error instanceof Error && error.message.includes('404')) return null
    throw error
  }
}

export async function fetchLockGraph(analysisId: string, includeConditionWaits = false): Promise<LockGraphView> {
  const { data } = await api.get<LockGraphView>(`/analysis/${analysisId}/lock-graph`, {
    params: { includeConditionWaits },
  })
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

export interface JfrReportSummary {
  id: string
  filename: string
  recordingStartEpochMs: number | null
  durationMillis: number
  sampleCount: number
  threadDumpCount: number
  dumpAnalysisId: string | null
  createdAtEpochMs: number
  expiresAtEpochMs: number
  jvmVersion: string | null
}

export interface JfrCaller {
  methodKey: string
  label: string
  samples: number
  parentPercent: number
  matched: boolean
  truncated: boolean
  callers: JfrCaller[]
  /** 汇总节点：其余调用者 / 栈被截断 / 到达栈底。真实调用者没有这个字段。 */
  kind?: 'others' | 'truncated' | 'bottom'
}

export type JfrTreeBasis = 'self' | 'inclusive'

export interface JfrHotspotRow {
  methodKey: string
  label: string
  selfSamples: number
  inclusiveSamples: number
  selfPercent: number
  inclusivePercent: number
  matched: boolean
  expand: boolean
  basis: JfrTreeBasis
  /** 按当前排序口径，每个时间桶的样本数，与 JfrCpuTimeline 的桶对齐 */
  timeline: number[]
  /** 估算的平均整机 CPU 占比（0~1），没有 CPU 数据时为 null */
  machineCpu: number | null
  callers: JfrCaller[]
}

export interface JfrHotspotList {
  totalSamples: number
  sort: 'self' | 'inclusive'
  /** false 表示旧版报告，调用者树二层以下按方法名聚合。 */
  exactPaths: boolean
  /** 调用栈被 JFR stackdepth 截断的样本数，旧版报告为 null */
  truncatedSamples: number | null
  /** false 表示旧版报告的调用栈没有时间信息，不能按时间区间统计 */
  rangeSupported: boolean
  /** 本次统计的时间桶区间 [rangeFrom, rangeTo)；统计整份录制时为 null */
  rangeFrom: number | null
  rangeTo: number | null
  methods: JfrHotspotRow[]
}

/** 热点时间桶区间 [from, to) */
export interface JfrBucketRange {
  from: number
  to: number
}

/** jdk.ContainerConfiguration；quotaCores 为 null 表示没设 CPU quota，内存为 null 表示不限制 */
export interface JfrContainerInfo {
  type: string | null
  quotaCores: number | null
  effectiveCpuCount: number | null
  cpuShares: number | null
  memoryLimit: number | null
  hostTotalMemory: number | null
}

export type JfrDeployment = 'container' | 'vm' | 'physical'

/**
 * jdk.CPULoad 按热点时间桶取平均，值是占操作系统全部逻辑核（容器里是节点）的比例（0~1），没有数据的桶为 null。
 * limitCores 是 JVM 实际可用的核数（容器取配额）；throttled 是桶所在窗口内被 CFS 限流的周期比例。
 */
export interface JfrCpuTimeline {
  startEpochMs: number
  endEpochMs: number
  bucketCount: number
  hwThreads: number | null
  cores: number | null
  samples: number[]
  jvmUser: (number | null)[]
  jvmSystem: (number | null)[]
  machineTotal: (number | null)[]
  deployment: JfrDeployment | null
  virtualization: string | null
  container: JfrContainerInfo | null
  limitCores: number | null
  throttled: (number | null)[] | null
}

export interface JfrThreadRow {
  javaThreadId: number
  name: string | null
  parentName: string | null
  startEpochMs: number | null
  endEpochMs: number | null
  existedBefore: boolean
  aliveAtEnd: boolean
  allocatedBytes: number | null
  recordedAllocatedBytes: number | null
}

export interface JfrVirtualGroup {
  stack: string[]
  count: number
  pinned: number
}

export interface JfrVirtualThread {
  javaThreadId: number
  startEpochMs: number | null
  endEpochMs: number | null
  stack: string[]
  pinned: number
}

export interface JfrLifecycleSeries {
  created: number[]
  ended: number[]
  alive: number[]
}

export interface JfrThreadPool {
  name: string
  type: string
  category: string
  total: number
  created: number
  ended: number
  aliveAtEnd: number
  peakAlive: number
  recordedAllocatedBytes: number
  series: JfrLifecycleSeries
  threads: JfrThreadRow[]
}

export interface JfrThreadOverview {
  windowStartEpochMs: number
  windowEndEpochMs: number
  bucketMillis: number
  totalThreads: number
  created: number
  ended: number
  aliveAtEnd: number
  peakActive: number | null
  series: JfrLifecycleSeries
  jvmActive: (number | null)[]
  pools: JfrThreadPool[]
  virtualThreads: {
    started: number
    ended: number
    pinned: number
    aggregated: boolean
    groups: JfrVirtualGroup[]
    threads: JfrVirtualThread[]
  }
}

export interface JfrPlatformThread extends JfrThreadRow {
  osThreadId: number | null
  parentJavaThreadId: number | null
  startStack: string[]
  cpu: { epochMs: number; user: number; system: number }[]
  allocation: { epochMs: number; allocated: number }[]
}

const JFR_TIMEOUT_MS = 300000

export async function uploadJfr(file: File, onProgress?: (percent: number) => void): Promise<JfrReportSummary> {
  const form = new FormData()
  form.append('file', file)
  const { data } = await api.post<JfrReportSummary>('/jfr/upload', form, {
    timeout: JFR_TIMEOUT_MS,
    onUploadProgress: (e) => {
      if (onProgress && e.total) onProgress(Math.round((e.loaded * 100) / e.total))
    },
  })
  return data
}

export async function fetchJfrReports(): Promise<JfrReportSummary[]> {
  const { data } = await api.get<JfrReportSummary[]>('/jfr')
  return data
}

export async function fetchJfrReport(id: string): Promise<JfrReportSummary> {
  const { data } = await api.get<JfrReportSummary>(`/jfr/${id}`)
  return data
}

export async function fetchJfrHotspots(
  id: string,
  q: string,
  sort: 'self' | 'inclusive',
  range: JfrBucketRange | null = null,
): Promise<JfrHotspotList> {
  const params = range ? { q, sort, from: range.from, to: range.to } : { q, sort }
  const { data } = await api.get<JfrHotspotList>(`/jfr/${id}/hotspots`, { params, timeout: 120000 })
  return data
}

/** 旧版报告或录制里没有 jdk.CPULoad 时返回 null */
export async function fetchJfrCpu(id: string): Promise<JfrCpuTimeline | null> {
  const { data, status } = await api.get<JfrCpuTimeline>(`/jfr/${id}/cpu`)
  return status === 204 || !data ? null : data
}

/** path 从热点根方法开始，到要展开的节点为止。 */
export async function fetchJfrCallers(
  id: string,
  path: string[],
  basis: JfrTreeBasis,
  q: string,
  range: JfrBucketRange | null = null,
): Promise<JfrCaller[]> {
  const body = { path, basis, q, from: range?.from ?? null, to: range?.to ?? null }
  const { data } = await api.post<JfrCaller[]>(`/jfr/${id}/hotspots/callers`, body, { timeout: 60000 })
  return data
}

export async function fetchJfrThreads(id: string): Promise<JfrThreadOverview> {
  const { data } = await api.get<JfrThreadOverview>(`/jfr/${id}/threads`)
  return data
}

export async function fetchJfrThread(id: string, javaThreadId: number): Promise<JfrPlatformThread> {
  const { data } = await api.get<JfrPlatformThread>(`/jfr/${id}/threads/${javaThreadId}`)
  return data
}

export async function openJfrDump(id: string): Promise<{ analysisId: string; dumpCount: number }> {
  const { data } = await api.post<{ analysisId: string; dumpCount: number }>(`/jfr/${id}/open-dump`)
  return data
}

export async function deleteJfrReport(id: string): Promise<void> {
  await api.delete(`/jfr/${id}`)
}
