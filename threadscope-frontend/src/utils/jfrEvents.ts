/** 报告里每块数据取自哪些 JFR 事件、用了哪些字段。 */
export interface JfrSourceRef {
  event: string
  use: string
}

/** 事件本身的含义和默认采集情况（以 JDK 17+ 自带的 default / profile 模板为准）。 */
export const JFR_EVENT_INFO: Record<string, string> = {
  'jdk.ExecutionSample': 'Java 线程的 CPU 采样，带调用栈。default 模板 20ms 一次，profile 10ms 一次。',
  'jdk.CPULoad': 'JVM 进程和整机的 CPU 使用率，每秒一条。',
  'jdk.CPUInformation': '硬件信息，每个 chunk 开始时一条。',
  'jdk.VirtualizationInformation': '虚拟化环境，每个 chunk 开始时一条。',
  'jdk.ContainerConfiguration': 'cgroup 配置，每个 chunk 开始时一条（JDK 17+）。',
  'jdk.ContainerCPUThrottling': 'cgroup CFS 限流统计，约 30 秒一条（JDK 17+）。',
  'jdk.JVMInformation': 'JVM 版本和启动参数，每个 chunk 开始时一条。',
  'jdk.ThreadDump': '完整的线程 Dump 文本。default 模板每个 chunk 一次，profile 60 秒一次。',
  'jdk.ObjectAllocationInNewTLAB': '线程申请新 TLAB 时记录一次，带调用栈。只有 profile 模板开启。',
  'jdk.ObjectAllocationOutsideTLAB': '对象直接在 TLAB 外分配（通常是大对象），带调用栈。只有 profile 模板开启。',
  'jdk.ObjectAllocationSample': '按线程限流的分配采样，带调用栈（JDK 16+）。default 模板每秒最多 150 条，profile 300 条。',
  'jdk.ThreadAllocationStatistics': '每个线程启动以来累计分配的字节数，是计数器而不是采样，default 模板也有。',
  'jdk.GarbageCollection': '每次 GC 一条：收集器、原因、总耗时、停顿合计和最长停顿。',
  'jdk.GCHeapSummary': '每次 GC 前后各一条，记录堆占用。',
  'jdk.GCHeapConfiguration': '堆的初始大小和最大大小。',
  'jdk.GCConfiguration': '新生代 / 老年代使用的收集器。',
  'jdk.ZAllocationStall': 'ZGC 来不及回收、线程申请内存时被迫等待，带调用栈。',
  'jdk.ResidentSetSize': '进程常驻内存 RSS（JDK 21+）。',
  'jdk.ThreadStart': '线程启动，带启动栈和父线程。',
  'jdk.ThreadEnd': '线程结束。',
  'jdk.ThreadCPULoad': '每个线程的 CPU 使用率，默认 10 秒一条。',
  'jdk.JavaThreadStatistics': 'JVM 线程计数：活跃数、守护线程数、启动以来的峰值，每秒一条。',
  'jdk.VirtualThreadStart': '虚拟线程启动，两个模板默认都关闭。',
  'jdk.VirtualThreadEnd': '虚拟线程结束，两个模板默认都关闭。',
  'jdk.VirtualThreadPinned': '虚拟线程阻塞时被钉在载体线程上，默认超过 20ms 才记录。',
}

const ALLOC_TLAB: JfrSourceRef[] = [
  { event: 'jdk.ObjectAllocationInNewTLAB', use: 'tlabSize 作为权重，stackTrace、objectClass' },
  { event: 'jdk.ObjectAllocationOutsideTLAB', use: 'allocationSize 作为权重，stackTrace、objectClass' },
]
const ALLOC_SAMPLE: JfrSourceRef[] = [
  { event: 'jdk.ObjectAllocationSample', use: 'weight 作为权重，stackTrace、objectClass' },
]

export function allocationEvents(source: 'tlab' | 'sample' | null | undefined): JfrSourceRef[] {
  return source === 'sample' ? ALLOC_SAMPLE : source === 'tlab' ? ALLOC_TLAB : [...ALLOC_TLAB, ...ALLOC_SAMPLE]
}

const MEASURED: JfrSourceRef = { event: 'jdk.ThreadAllocationStatistics', use: 'allocated：每个线程相邻两条的差值，按时间摊到各时间段' }
const RECORDED = '录制前已存在的线程取末值 − 首值，录制中新建的线程从 0 算'

export const JFR_SOURCES = {
  reportMeta: [
    { event: 'jdk.ExecutionSample', use: '样本数；录制时长取采样和分配事件覆盖的时间段' },
    { event: 'jdk.ThreadDump', use: 'Dump 段数' },
    { event: 'jdk.JVMInformation', use: 'jvmVersion' },
  ],
  cpuChart: [
    { event: 'jdk.CPULoad', use: 'jvmUser / jvmSystem 画 JVM 曲线，machineTotal 画整机' },
    { event: 'jdk.CPUInformation', use: 'hwThreads 换算核数' },
    { event: 'jdk.ContainerConfiguration', use: 'CPU 配额，换算成占配额的比例' },
    { event: 'jdk.ContainerCPUThrottling', use: 'cpuThrottledSlices / cpuElapsedSlices 画限流区域' },
    { event: 'jdk.VirtualizationInformation', use: '运行环境标签' },
    { event: 'jdk.ExecutionSample', use: '选中方法的曲线 = JVM CPU × 该方法的采样占比' },
  ],
  hotMethods: [
    { event: 'jdk.ExecutionSample', use: 'stackTrace：栈顶帧计 self，栈上出现计 inclusive，下一帧是调用者' },
    { event: 'jdk.CPULoad', use: '「≈ x 核」= JVM CPU × 该方法的采样占比' },
  ],
  measured: [MEASURED],
  gc: [{ event: 'jdk.GarbageCollection', use: 'name、duration、sumOfPauses、longestPause' }],
  stall: [{ event: 'jdk.ZAllocationStall', use: 'duration、eventThread' }],
  stallDetail: [{ event: 'jdk.ZAllocationStall', use: 'stackTrace、duration、eventThread、type、size' }],
  heapFloor: [
    { event: 'jdk.GCHeapSummary', use: 'when = After GC 时的 heapUsed' },
    { event: 'jdk.GCHeapConfiguration', use: 'maxSize 作为堆上限' },
  ],
  collector: [{ event: 'jdk.GCConfiguration', use: 'youngCollector、oldCollector' }],
  memoryChart: [
    MEASURED,
    { event: 'jdk.GCHeapSummary', use: 'heapUsed：GC 前后的堆占用' },
    { event: 'jdk.GCHeapConfiguration', use: 'maxSize：堆上限' },
    { event: 'jdk.ResidentSetSize', use: 'size：RSS 虚线' },
    { event: 'jdk.GarbageCollection', use: '底部 GC 刻度' },
    { event: 'jdk.ZAllocationStall', use: '红色停顿竖带' },
  ],
  pools: [{ event: 'jdk.ThreadAllocationStatistics', use: `allocated：${RECORDED}，按线程名归到线程池` }],
  threadKpi: [
    { event: 'jdk.ThreadStart', use: '录制中启动的线程' },
    { event: 'jdk.ThreadEnd', use: '录制中结束的线程' },
    { event: 'jdk.ThreadCPULoad', use: '录制前已存在的线程也会周期性出现在这里' },
    { event: 'jdk.ThreadAllocationStatistics', use: '同上，每个线程一条' },
  ],
  threadStart: [{ event: 'jdk.ThreadStart', use: 'thread、startTime' }],
  threadEnd: [{ event: 'jdk.ThreadEnd', use: 'thread、startTime' }],
  threadNet: [
    { event: 'jdk.ThreadStart', use: '新建数' },
    { event: 'jdk.ThreadEnd', use: '销毁数；没有 ThreadEnd 的线程算结束时存活' },
  ],
  threadPeak: [{ event: 'jdk.JavaThreadStatistics', use: 'peakCount 的最大值' }],
  threadChart: [
    { event: 'jdk.ThreadStart', use: '新建柱和存活线程数' },
    { event: 'jdk.ThreadEnd', use: '销毁柱和存活线程数' },
    { event: 'jdk.JavaThreadStatistics', use: 'activeCount 画 JVM 活跃线程虚线' },
  ],
  threadTable: [
    { event: 'jdk.ThreadStart', use: '开始时间、启动栈、父线程' },
    { event: 'jdk.ThreadEnd', use: '结束时间' },
    { event: 'jdk.ThreadCPULoad', use: '线程 CPU 曲线；也用来发现录制前已存在的线程' },
    { event: 'jdk.ThreadAllocationStatistics', use: `allocated：录制期分配 = ${RECORDED}` },
  ],
  threadCpu: [{ event: 'jdk.ThreadCPULoad', use: 'user + system' }],
  threadAlloc: [{ event: 'jdk.ThreadAllocationStatistics', use: 'allocated：线程启动以来的累计值' }],
  threadStack: [{ event: 'jdk.ThreadStart', use: 'stackTrace、parentThread' }],
  virtualThreads: [
    { event: 'jdk.VirtualThreadStart', use: '启动数和启动栈' },
    { event: 'jdk.VirtualThreadEnd', use: '结束数' },
    { event: 'jdk.VirtualThreadPinned', use: '钉住次数' },
  ],
  threadDump: [{ event: 'jdk.ThreadDump', use: 'result：Dump 文本，按事件时间排序' }],
} satisfies Record<string, JfrSourceRef[]>
