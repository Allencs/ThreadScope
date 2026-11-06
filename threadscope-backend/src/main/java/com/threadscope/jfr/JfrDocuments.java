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

    /** 去重后的一条调用栈。frames 是 {@link HotspotDocument#frames()} 的下标，[0] 为栈顶。 */
    public record StackSample(int[] frames, long samples, boolean truncated, int[] buckets, long[] bucketSamples) {
        /** buckets / bucketSamples 是稀疏的时间桶分布（升序），为 null 表示旧版报告，不支持按时间区间统计。 */
        public StackSample(int[] frames, long samples, boolean truncated) {
            this(frames, samples, truncated, null, null);
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
        long durationMillis
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
        String jvmVersion
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
