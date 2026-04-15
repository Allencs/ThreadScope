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

export interface ThreadInfo {
  name: string
  threadNumber: number
  daemon: boolean
  priority: number
  osPriority: number
  cpuTime: string | null
  elapsed: string | null
  tid: string
  nid: string
  nidDecimal: number
  state: ThreadState
  stateDetail: string | null
  stackAddress: string | null
  stackTrace: StackFrame[]
  lockActions: LockAction[]
  ownableSynchronizers: string[]
}

export interface ThreadSummary {
  name: string
  state: ThreadState
  daemon: boolean
  stackDepth: number
  hasLockActions: boolean
}

export interface LockInfo {
  lockAddress: string
  lockClassName: string
  holderThreadName: string | null
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

export interface ThreadPoolInfo {
  poolName: string
  poolType: string
  totalThreads: number
  stateDistribution: Record<ThreadState, number>
  threadNames: string[]
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
}

export interface UploadResponse {
  analysisId: string
  fileName: string
  totalThreads: number
  parseTimeMs: number
  jvmVersion: string
  analyzedAt: string
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
