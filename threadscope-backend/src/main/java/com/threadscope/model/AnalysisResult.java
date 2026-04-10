package com.threadscope.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 完整的分析结果 — 包含解析后的全部数据和诊断结果。
 * 这是后端返回给前端的顶层数据结构。
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
    HealthReport healthReport
) {
    public int totalThreads() {
        return threads != null ? threads.size() : 0;
    }
}
