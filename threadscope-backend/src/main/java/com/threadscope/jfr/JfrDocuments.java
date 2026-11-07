package com.threadscope.jfr;

import java.util.List;
import java.util.Map;

/**
 * 持久化到 PostgreSQL 的 JFR 解析结果。原始采样事件不保存。
 */
public final class JfrDocuments {

    private JfrDocuments() {}

    public record MethodStat(
        long self,
        long inclusive,
        Map<String, Long> callers,
        Map<String, Long> selfBuckets,
        Map<String, Long> inclusiveBuckets
    ) {}

    /**
     * 去重后的一条调用栈。frames 是 {@link HotspotDocument#frames()} 的下标，[0] 为栈顶。
     * 分配栈里 samples / bucketSamples 是估算的字节数，type 是 {@link AllocationProfile#types()} 的下标；CPU 栈的 type 为 null。
     */
    public record StackSample(int[] frames, long samples, boolean truncated, int[] buckets, long[] bucketSamples, Integer type) {
        /** buckets / bucketSamples 是稀疏的时间桶分布（升序），为 null 表示旧版报告，不支持按时间区间统计。 */
        public StackSample(int[] frames, long samples, boolean truncated) {
            this(frames, samples, truncated, null, null, null);
        }

        public StackSample(int[] frames, long samples, boolean truncated, int[] buckets, long[] bucketSamples) {
            this(frames, samples, truncated, buckets, bucketSamples, null);
        }
    }

    /**
     * jdk.CPULoad：三个值都是占整机 CPU 的比例（0~1）。整机指操作系统可见的全部逻辑核，
     * 容器里就是所在节点的全部核（/proc/stat），不是容器配额。
     */
    public record CpuLoadPoint(long epochMs, float jvmUser, float jvmSystem, float machineTotal) {}

    /**
     * jdk.ContainerConfiguration，只有 JVM 识别出 cgroup 限制时才会产生。
     * quotaCores = cpuQuota / cpuSlicePeriod，未设置 quota 时为 null；内存为 null 表示不限制。
     */
    public record ContainerInfo(
        String type,
        Double quotaCores,
        Long effectiveCpuCount,
        Long cpuShares,
        Long memoryLimit,
        Long hostTotalMemory
    ) {}

    /** jdk.ContainerCPUThrottling：cgroup 自容器启动以来的累计值，需要相邻两点相减。 */
    public record ThrottlePoint(long epochMs, long elapsedSlices, long throttledSlices, long throttledNanos) {}

    /**
     * hwThreads / cores 来自 jdk.CPUInformation，是操作系统可见的核数，容器里不会按配额缩小。
     * virtualization 来自 jdk.VirtualizationInformation，container 为 null 表示不在容器里（或 JDK 未识别）。
     */
    public record CpuDocument(
        Integer hwThreads,
        Integer cores,
        List<CpuLoadPoint> points,
        String virtualization,
        ContainerInfo container,
        List<ThrottlePoint> throttling
    ) {
        public CpuDocument(Integer hwThreads, Integer cores, List<CpuLoadPoint> points) {
            this(hwThreads, cores, points, null, null, null);
        }
    }

    /** frames / stacks 为 null 表示旧版解析结果，只能按方法名聚合查调用者；cpu 为 null 表示没有采集 CPU 负载。 */
    public record HotspotDocument(
        long totalSamples,
        long recordingStartEpochMs,
        long recordingEndEpochMs,
        int bucketCount,
        List<Long> bucketSamples,
        Map<String, MethodStat> methods,
        List<String> frames,
        List<StackSample> stacks,
        CpuDocument cpu
    ) {
        public HotspotDocument(long totalSamples, long recordingStartEpochMs, long recordingEndEpochMs,
                               int bucketCount, List<Long> bucketSamples, Map<String, MethodStat> methods) {
            this(totalSamples, recordingStartEpochMs, recordingEndEpochMs, bucketCount, bucketSamples, methods, null, null, null);
        }
    }

    /**
     * 与热点时间桶对齐的 CPU 曲线。jvmUser / jvmSystem / machineTotal 是桶内 jdk.CPULoad 的平均值，
     * 没有数据的桶为 null；samples 是桶内的 CPU 采样数。
     * deployment：container / vm / physical，无法判断时为 null。
     * limitCores 是 JVM 实际能用的核数：容器取 quota（没有 quota 取 effectiveCpuCount），否则为 hwThreads。
     * throttled 是桶所在统计窗口内被 CFS 限流的调度周期比例（0~1），没有数据时整个列表为 null。
     */
    public record CpuTimeline(
        long startEpochMs,
        long endEpochMs,
        int bucketCount,
        Integer hwThreads,
        Integer cores,
        List<Long> samples,
        List<Float> jvmUser,
        List<Float> jvmSystem,
        List<Float> machineTotal,
        String deployment,
        String virtualization,
        ContainerInfo container,
        Double limitCores,
        List<Float> throttled
    ) {}

    public record CpuPoint(long epochMs, float user, float system) {}

    public record AllocPoint(long epochMs, long allocated) {}

    public record PlatformThread(
        long javaThreadId,
        String name,
        Long osThreadId,
        Long parentJavaThreadId,
        String parentName,
        Long startEpochMs,
        Long endEpochMs,
        boolean existedBefore,
        boolean aliveAtEnd,
        List<String> startStack,
        List<CpuPoint> cpu,
        List<AllocPoint> allocation
    ) {}

    public record StatPoint(
        long epochMs,
        long active,
        long daemon,
        long accumulated,
        long peak
    ) {}

    public record VirtualThread(
        long javaThreadId,
        Long startEpochMs,
        Long endEpochMs,
        List<String> stack,
        int pinned
    ) {}

    public record VirtualGroup(List<String> stack, long count, long pinned) {}

    public record VirtualSummary(
        long started,
        long ended,
        long pinned,
        boolean aggregated,
        List<VirtualGroup> groups,
        List<VirtualThread> threads
    ) {}

    public record ThreadDocument(
        List<PlatformThread> platform,
        List<StatPoint> statistics,
        VirtualSummary virtualThreads
    ) {}

    public record Summary(
        String jvmVersion,
        int platformThreads,
        long virtualStarted,
        long virtualPinned
    ) {}

    public record ParseResult(
        HotspotDocument hotspot,
        ThreadDocument threads,
        Summary summary,
        List<String> threadDumps,
        long durationMillis,
        MemoryDocument memory
    ) {}

    /**
     * 一种分配事件来源的调用栈，复用热点结构：totalSamples / bucketSamples / 每条栈的 samples 都是估算的字节数，methods 为空。
     * source：tlab = ObjectAllocationInNewTLAB（按 TLAB 大小计）+ ObjectAllocationOutsideTLAB（按对象大小计），与 JMC 的估算口径一致；
     * sample = ObjectAllocationSample（按 weight 计，default 模板也有）。
     * cover 是这种事件实际出现的时间范围，可能只是录制的一部分。
     */
    public record AllocationProfile(
        String source,
        long events,
        Long coverStartEpochMs,
        Long coverEndEpochMs,
        List<String> types,
        HotspotDocument stacks
    ) {}

    /** jdk.GarbageCollection。ZGC 的 duration 是并发周期长度，停顿只看 pauseNanos。 */
    public record GcCollection(long gcId, long epochMs, String name, String cause,
                               long durationNanos, long pauseNanos, long longestPauseNanos) {}

    /** jdk.GCHeapSummary：每次 GC 前后各一条。 */
    public record HeapPoint(long epochMs, boolean afterGc, long used, long committed) {}

    public record MemoryPoint(long epochMs, long value) {}

    /**
     * jdk.ZAllocationStall：ZGC 来不及回收时，分配线程被迫等待。size 是触发停顿的页分配大小，
     * stack 是 GcDocument.stallStacks 的下标；旧版解析的报告这两个字段为 null。
     */
    public record StallEvent(long epochMs, long durationNanos, String thread, String type, Long size, Integer stack) {}

    /** frames 是 GcDocument.stallFrames 的下标，栈顶在前。 */
    public record StallStack(List<Integer> frames, boolean truncated) {}

    public record GcDocument(
        String youngCollector,
        String oldCollector,
        Long heapMax,
        Long heapInitial,
        List<GcCollection> collections,
        List<HeapPoint> heap,
        long stallCount,
        List<StallEvent> stalls,
        List<MemoryPoint> metaspace,
        List<MemoryPoint> rss,
        List<MemoryPoint> container,
        Long containerLimit,
        List<String> stallFrames,
        List<StallStack> stallStacks
    ) {}

    /**
     * 时间桶与热点一致。measuredBytes 是 jdk.ThreadAllocationStatistics 相邻两次的差值按桶累加，
     * 是实际分配量而非估算；没有该事件时为 null。measuredIntervalMs 是它的采集间隔（中位数）。
     */
    public record MemoryDocument(
        long startEpochMs,
        long endEpochMs,
        int bucketCount,
        List<AllocationProfile> profiles,
        List<Long> measuredBytes,
        Long measuredIntervalMs,
        GcDocument gc
    ) {}

    public record AllocationSource(String source, long events, long estimatedBytes,
                                   Long coverStartEpochMs, Long coverEndEpochMs, List<Long> bucketBytes) {}

    public record GcSummary(String name, long count, long pauseNanos, long longestPauseNanos, long durationNanos) {}

    public record ThreadAllocation(String name, long bytes) {}

    public record PoolAllocation(String name, long bytes, int threads, List<ThreadAllocation> top) {}

    public record StallThread(String name, long count, long durationNanos) {}

    public record StallFrame(String methodKey, String label, boolean business) {}

    /** 一条完整的停顿调用栈。anchor 是从栈顶数第一个业务帧的位置，没有为 null。 */
    public record StallTrace(List<StallFrame> frames, Integer anchor, boolean truncated, long count, long durationNanos) {}

    public record StallEventRow(long epochMs, long durationNanos, String thread, String type, Long size) {}

    /**
     * 一组停顿：按调用栈分组时一条栈一组，按业务代码分组时同一个首业务帧的栈归为一组。
     * traces 是组内耗时最多的几条栈，events 是组内耗时最长的几次停顿。
     */
    public record StallGroup(
        String key,
        String label,
        boolean business,
        long count,
        long durationNanos,
        long longestNanos,
        long sizeBytes,
        double percent,
        int threadCount,
        List<StallThread> threads,
        Map<String, Long> types,
        int stackCount,
        List<StallTrace> traces,
        List<StallEventRow> events
    ) {}

    /** group：stack / business。stacksAvailable=false 表示旧版解析的报告，停顿事件没有保存调用栈。 */
    public record StallReport(
        String group,
        String thread,
        Integer rangeFrom,
        Integer rangeTo,
        boolean stacksAvailable,
        long count,
        long durationNanos,
        long longestNanos,
        int threadCount,
        int groupCount,
        List<StallGroup> groups
    ) {}

    /** level：critical / warn / info。 */
    public record MemoryFinding(String level, String title, String detail) {}

    /**
     * 内存标签页的概览。所有 List 都按热点时间桶对齐，没有数据的桶为 null。
     * heapPeak 是桶内 GC 前后堆占用的最大值，heapFloor 是桶内 GC 后占用的最小值（存活对象的下限）。
     */
    public record MemoryOverview(
        long startEpochMs,
        long endEpochMs,
        int bucketCount,
        List<AllocationSource> sources,
        List<Long> measuredBytes,
        Long measuredIntervalMs,
        Long measuredTotal,
        String youngCollector,
        String oldCollector,
        Long heapMax,
        List<GcSummary> collectors,
        List<Long> gcCount,
        List<Long> gcPauseNanos,
        List<Long> heapPeak,
        List<Long> heapFloor,
        List<Long> heapCommitted,
        long stallCount,
        long stallNanos,
        long longestStallNanos,
        List<Long> stallBuckets,
        List<Long> stallNanosBuckets,
        List<StallThread> stallThreads,
        List<Long> metaspace,
        List<Long> rss,
        List<Long> container,
        Long containerLimit,
        List<PoolAllocation> pools,
        List<MemoryFinding> findings
    ) {}

    /** 分配点：栈顶方法 + 对象类型。percent 是占所在行（按当前口径）字节数的比例。 */
    public record AllocationSite(String methodKey, String label, String type, long bytes, double percent) {}

    /**
     * attributedBytes 是"首个业务帧"口径下归到该方法的字节数：每条分配栈从栈顶往下找到的第一个业务方法记一次。
     * 只在按业务排序时有值。
     */
    public record AllocationRow(
        String methodKey,
        String label,
        long selfBytes,
        long inclusiveBytes,
        Long attributedBytes,
        double selfPercent,
        double inclusivePercent,
        Double attributedPercent,
        boolean matched,
        boolean expand,
        String basis,
        List<Long> timeline,
        List<AllocationSite> sites,
        List<HotspotCaller> callers
    ) {}

    public record TypeRow(String name, long bytes, double percent) {}

    /**
     * totalBytes 是当前统计范围（时间区间 + 对象类型）内的估算字节数，rangeBytes 是不按类型过滤时的总量。
     * unattributedBytes 是按业务排序时整条栈都没有业务帧的字节数。
     */
    public record AllocationList(
        String source,
        String sort,
        long totalBytes,
        long rangeBytes,
        String type,
        boolean rangeSupported,
        Integer rangeFrom,
        Integer rangeTo,
        Long truncatedBytes,
        Long unattributedBytes,
        List<TypeRow> types,
        List<AllocationRow> methods
    ) {}

    /**
     * kind 为 null 是真实调用者；others / truncated / bottom 是汇总节点，
     * 分别表示未列出的其余调用者、被 JFR stackdepth 截断的栈、在此处到达栈底。
     */
    public record HotspotCaller(
        String methodKey,
        String label,
        long samples,
        double parentPercent,
        boolean matched,
        boolean truncated,
        List<HotspotCaller> callers,
        String kind
    ) {
        public HotspotCaller(String methodKey, String label, long samples, double parentPercent,
                             boolean matched, boolean truncated, List<HotspotCaller> callers) {
            this(methodKey, label, samples, parentPercent, matched, truncated, callers, null);
        }
    }

    /**
     * basis 是调用者树第一层的分母：self 样本或 inclusive 样本。
     * timeline 是按当前排序口径（self / inclusive）每个时间桶的样本数；
     * machineCpu 是估算的平均整机 CPU 占比：Σ 桶内 JVM CPU × 该方法在桶内的样本占比，没有 CPU 数据时为 null。
     */
    public record HotspotRow(
        String methodKey,
        String label,
        long selfSamples,
        long inclusiveSamples,
        double selfPercent,
        double inclusivePercent,
        boolean matched,
        boolean expand,
        String basis,
        List<Long> timeline,
        Double machineCpu,
        List<HotspotCaller> callers
    ) {}

    /** truncatedSamples：调用栈超过 JFR stackdepth 被截断的样本数，旧版报告为 null。 */
    /**
     * totalSamples 是统计范围内的样本数：选了时间区间时只算区间内的桶 [rangeFrom, rangeTo)，否则是整份录制。
     * rangeSupported 为 false 表示旧版报告的调用栈没有时间信息，不能按区间统计。
     */
    public record HotspotList(long totalSamples, String sort, boolean exactPaths, Long truncatedSamples,
                              boolean rangeSupported, Integer rangeFrom, Integer rangeTo,
                              List<HotspotRow> methods) {}

    /** 热点时间桶区间 [from, to)。 */
    public record BucketRange(int from, int to) {
        public static BucketRange of(Integer from, Integer to, int bucketCount) {
            if (from == null || to == null) return null;
            int a = Math.max(0, Math.min(from, to));
            int b = Math.min(bucketCount, Math.max(from, to));
            if (b <= a || (a == 0 && b >= bucketCount)) return null;
            return new BucketRange(a, b);
        }
    }

    public record ReportSummary(
        String id,
        String filename,
        Long recordingStartEpochMs,
        long durationMillis,
        long sampleCount,
        int threadDumpCount,
        String dumpAnalysisId,
        long createdAtEpochMs,
        long expiresAtEpochMs,
        String jvmVersion,
        String createdBy
    ) {}

    public record ThreadRow(
        long javaThreadId,
        String name,
        String parentName,
        Long startEpochMs,
        Long endEpochMs,
        boolean existedBefore,
        boolean aliveAtEnd,
        Long allocatedBytes,
        Long recordedAllocatedBytes
    ) {}

    /** 每个时间桶内创建/销毁的线程数，以及桶结束时刻仍存活的线程数。 */
    public record LifecycleSeries(int[] created, int[] ended, int[] alive) {}

    public record ThreadPoolGroup(
        String name,
        String type,
        String category,
        int total,
        int created,
        int ended,
        int aliveAtEnd,
        int peakAlive,
        long recordedAllocatedBytes,
        LifecycleSeries series,
        List<ThreadRow> threads
    ) {}

    public record ThreadOverview(
        long windowStartEpochMs,
        long windowEndEpochMs,
        long bucketMillis,
        int totalThreads,
        int created,
        int ended,
        int aliveAtEnd,
        Long peakActive,
        LifecycleSeries series,
        List<Long> jvmActive,
        List<ThreadPoolGroup> pools,
        VirtualSummary virtualThreads
    ) {}
}
