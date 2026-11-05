// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ThreadScope TypeScript 类型定义
// 与后端 Java 领域模型 1:1 映射
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

export type ThreadState =
  | 'NEW'
  | 'RUNNABLE'
  | 'BLOCKED'
  | 'WAITING'
  | 'TIMED_WAITING'
  | 'TERMINATED'
  | 'UNKNOWN'

export interface StackFrame {
  className: string
  methodName: string
  source: string
  lineNumber: number
}

export type LockAction =
  | { type: 'Held'; lockAddress: string; lockClassName: string; frameIndex: number }
  | { type: 'WaitingToLock'; lockAddress: string; lockClassName: string; frameIndex: number }
  | { type: 'ParkingToWaitFor'; lockAddress: string; lockClassName: string; frameIndex: number }
  | { type: 'WaitingOn'; lockAddress: string; lockClassName: string; frameIndex: number }

export type ThreadKind = 'PLATFORM' | 'VIRTUAL' | 'CARRIER'

export interface ThreadInfo {
  name: string
  threadNumber: number
  daemon: boolean
  priority: number
  osPriority: number
  cpuTime: string | null
  elapsed: string | null
  tid: string | null
  nid: string
  nidDecimal: number
  state: ThreadState
  stateDetail: string | null
  stackAddress: string | null
  stackTrace: StackFrame[]
  lockActions: LockAction[]
  ownableSynchronizers: string[]
  /** 单份 dump 内唯一的线程标识 (线程名可能重名) */
  key: string
  kind: ThreadKind
  /** VIRTUAL: 所在载体线程；CARRIER: 正在承载的虚拟线程 */
  carrier?: string | null
  /** jcmd 导出的线程容器 */
  container?: string | null
  /** 状态由栈顶推断 */
  stateInferred: boolean
}

export interface ThreadSummary {
  name: string
  key: string
  kind: ThreadKind
  state: ThreadState
  daemon: boolean
  stackDepth: number
  hasLockActions: boolean
}

export type CodeLayer = 'JDK' | 'FRAMEWORK' | 'BUSINESS'

export interface LockInfo {
  lockAddress: string
  lockClassName: string
  holderThreadName: string | null
  holderThreadKey?: string | null
  waitingThreadNames: string[]
}

export interface DeadlockChain {
  threadNames: string[]
  lockAddresses: string[]
  description: string
}

export interface DeadlockInfo {
  chains: DeadlockChain[]
}

export type PoolCategory =
  | 'WEB' | 'RPC' | 'DB' | 'MQ' | 'CACHE' | 'SCHEDULER' | 'ASYNC'
  | 'EVENT_LOOP' | 'VIRTUAL' | 'JVM' | 'LOGGING' | 'CUSTOM' | 'ANONYMOUS'

export interface ThreadPoolInfo {
  poolName: string
  poolType: string
  category: PoolCategory
  totalThreads: number
  stateDistribution: Record<ThreadState, number>
  threadNames: string[]
  threadKeys: string[]
  /** 按栈判定为等新任务的空闲线程 */
  idleCount: number
  /** 执行任务中 / 在任务中等资源的线程 */
  busyCount: number
  hints: string[]
}

export interface MethodHotspot {
  className: string
  methodName: string
  occurrences: number
  percentage: number
  sampleThreads: string[]
}

export interface StackAggregateGroup {
  fingerprint: string
  threadCount: number
  representativeStack: StackFrame[]
  threadNames: string[]
  stateDistribution: Record<ThreadState, number>
  meaningfulTopFrame?: StackFrame
  groupLabel?: string
}

export interface RiskItem {
  category: string
  level: 'HEALTHY' | 'INFO' | 'WARNING' | 'CRITICAL'
  title: string
  description: string
  affectedThreads: string[]
}

export interface HealthReport {
  overallLevel: 'HEALTHY' | 'INFO' | 'WARNING' | 'CRITICAL'
  risks: RiskItem[]
}

export interface DataQualityIssue {
  code: string
  level: 'INFO' | 'WARNING'
  title: string
  impact: string
  suggestion: string
}

export interface PinnedThread {
  threadName: string
  threadKey: string
  kind: ThreadKind
  reason: string
  topFrames: string[]
  blockingFrame: string | null
}

export interface VirtualThreadReport {
  virtualCount: number
  carrierCount: number
  mountedCount: number
  pinnedCount: number
  jvmMajorVersion: number
  visibility: 'FULL' | 'MOUNTED_ONLY'
  virtualStates: Partial<Record<ThreadState, number>>
  pinned: PinnedThread[]
  containers: { container: string; count: number; stateDistribution: Partial<Record<ThreadState, number>> }[]
}

export interface SnapshotRef {
  index: number
  analysisId: string
  timestamp: string | null
  totalThreads: number
  healthLevel: RiskItem['level'] | null
  deadlockCount: number
  latest: boolean
}

export interface OverviewData {
  analysisId: string
  fileName: string
  jvmVersion: string
  totalThreads: number
  daemonCount: number
  nonDaemonCount: number
  parseTimeMs: number
  analyzedAt: string
  stateDistribution: Record<ThreadState, number>
  healthReport: HealthReport
  deadlockCount: number
  threadPoolCount: number
  lockContentionCount: number
  dumpCount: number
  dumpFormat: 'JSTACK' | 'JCMD_JSON' | 'JCMD_TEXT'
  snapshotIndex: number
  snapshotTimestamp: string | null
  snapshots: SnapshotRef[]
  dataQuality: DataQualityIssue[]
  virtualThreads: VirtualThreadReport | null
  applicationName: string | null
  businessPackages: string[]
  suggestedBusinessPackages: string[]
}

// ── 锁等待关系图 ──
export type LockGraphNodeType = 'THREAD' | 'LOCK' | 'GROUP'
export type LockGraphEdgeType = 'WAITING_TO_LOCK' | 'PARKING' | 'WAITING_ON' | 'HELD_BY'

export interface LockGraphNode {
  id: string
  type: LockGraphNodeType
  label: string
  threadKey: string | null
  state: ThreadState | null
  topMethod: string | null
  lockAddress: string | null
  lockClassName: string | null
  count: number
  members: { name: string; key: string }[]
  root: boolean
  deadlock: boolean
  blockedBehind: number
}

export interface LockGraphEdge {
  from: string
  to: string
  type: LockGraphEdgeType
  deadlock: boolean
}

export interface LockGraphView {
  nodes: LockGraphNode[]
  edges: LockGraphEdge[]
  totalWaiters: number
  truncated: boolean
  hiddenComponents: number
}

export interface CodeLayerConfig {
  jdkPrefixes: string[]
  frameworkPrefixes: string[]
  defaultBusinessPackages: string[]
}

// ── 多 Dump 对比 ──
export interface SnapshotSummary {
  index: number
  timestamp: string | null
  totalThreads: number
  stateDistribution: Record<ThreadState, number>
}

export interface StuckThread {
  threadName: string
  threadKey: string
  state: ThreadState
  dumpsSeen: number
  topMethod: string
  topFrames: string[]
}

export interface CpuDelta {
  threadName: string
  threadKey: string
  cpuMsDelta: number
  firstCpuMs: number
  lastCpuMs: number
  lastState: ThreadState
  topMethod: string
}

export interface ThreadTrend {
  groupName: string
  counts: number[]
}

export interface DumpComparison {
  dumpCount: number
  snapshots: SnapshotSummary[]
  stuckThreads: StuckThread[]
  topCpuThreads: CpuDelta[]
  threadTrends: ThreadTrend[]
  jvmRestartSuspected: boolean
  matchedThreads: number
}

// ── 调用树 (火焰图) ──
export interface CallTreeNode {
  name: string
  value: number
  children: CallTreeNode[]
}

// ── top -H CPU 关联 ──
export interface CorrelatedThread {
  threadName: string
  nid: string
  nidDecimal: number
  cpuPercent: number
  state: ThreadState
  topMethod: string
}

export interface CorrelationResult {
  threads: CorrelatedThread[]
  parsedEntries: number
  matched: number
}

export interface UploadResponse {
  analysisId: string
  fileName: string
  totalThreads: number
  parseTimeMs: number
  jvmVersion: string
  analyzedAt: string
  snapshotCount: number
}

export interface UploadOptions {
  businessPackages?: string[]
  applicationName?: string | null
}

export interface ThreadListResponse {
  total: number
  page: number
  size: number
  threads: ThreadInfo[]
}

// ── State Color Mapping ──
export const STATE_COLORS: Record<ThreadState, string> = {
  NEW: '#7c3aed',
  RUNNABLE: '#16a34a',
  BLOCKED: '#dc2626',
  WAITING: '#d97706',
  TIMED_WAITING: '#2563eb',
  TERMINATED: '#9ca3af',
  UNKNOWN: '#9ca3af',
}

export const STATE_LABELS: Record<ThreadState, string> = {
  NEW: 'NEW',
  RUNNABLE: 'RUNNABLE',
  BLOCKED: 'BLOCKED',
  WAITING: 'WAITING',
  TIMED_WAITING: 'TIMED_WAIT',
  TERMINATED: 'TERMINATED',
  UNKNOWN: 'UNKNOWN',
}
