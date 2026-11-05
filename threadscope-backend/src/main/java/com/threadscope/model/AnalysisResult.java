package com.threadscope.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 单份 dump 快照的完整分析结果 — 包含解析后的全部数据和诊断结果。
 * 多 dump 上传时每份快照各有一个 AnalysisResult，共享同一个 {@link DumpComparison}。
 */
public record AnalysisResult(
    // ── 元信息 ──
    String analysisId,
    String fileName,
    String jvmVersion,
    Instant analyzedAt,
    long parseTimeMs,

    // ── 核心数据 ──
    List<ThreadInfo> threads,
    Map<ThreadState, Long> stateDistribution,

    // ── 诊断结果 ──
    DeadlockInfo deadlocks,
    List<LockInfo> lockInfos,
    List<ThreadPoolInfo> threadPools,
    List<MethodHotspot> methodHotspots,
    List<StackAggregateGroup> stackAggregations,

    // ── 健康评估 ──
    HealthReport healthReport,

    // ── 多 dump 对比 (单 dump 时为 null) ──
    DumpComparison comparison,

    // ── 快照信息 ──
    int snapshotIndex,
    String snapshotTimestamp,
    String dumpFormat,

    // ── 数据质量 / 虚拟线程 / 代码归属 ──
    List<DataQualityIssue> dataQuality,
    VirtualThreadReport virtualThreads,
    String applicationName,
    List<String> businessPackages,
    List<String> suggestedBusinessPackages
) {
    public static final String FORMAT_JSTACK = "JSTACK";
    public static final String FORMAT_JCMD_JSON = "JCMD_JSON";
    public static final String FORMAT_JCMD_TEXT = "JCMD_TEXT";

    /** 单快照结果的便捷构造 (测试 / 工具场景)。 */
    public AnalysisResult(
            String analysisId, String fileName, String jvmVersion, Instant analyzedAt, long parseTimeMs,
            List<ThreadInfo> threads, Map<ThreadState, Long> stateDistribution,
            DeadlockInfo deadlocks, List<LockInfo> lockInfos, List<ThreadPoolInfo> threadPools,
            List<MethodHotspot> methodHotspots, List<StackAggregateGroup> stackAggregations,
            HealthReport healthReport, DumpComparison comparison) {
        this(analysisId, fileName, jvmVersion, analyzedAt, parseTimeMs, threads, stateDistribution,
            deadlocks, lockInfos, threadPools, methodHotspots, stackAggregations, healthReport, comparison,
            0, null, FORMAT_JSTACK, List.of(), null, null, List.of(), List.of());
    }

    public int totalThreads() {
        return threads != null ? threads.size() : 0;
    }

    public int dumpCount() {
        return comparison != null ? comparison.dumpCount() : 1;
    }
}
