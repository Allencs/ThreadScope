package com.threadscope.model;

import java.util.List;
import java.util.Map;

/**
 * 多 Dump 对比分析结果。
 *
 * 单个 dump 是瞬间快照，无法区分"正好在等"和"一直卡着"；
 * 间隔抓取的多个 dump 对比才能回答这个问题：
 *   - 栈完全不动的线程 → 真挂起/死循环/长事务
 *   - CPU 时间差值     → 真实 CPU 消耗 (单快照的 cpu= 累计值没有意义)
 *   - 线程数趋势       → 线程泄漏
 */
public record DumpComparison(
    int dumpCount,
    List<SnapshotSummary> snapshots,
    List<StuckThread> stuckThreads,
    List<CpuDelta> topCpuThreads,
    List<ThreadTrend> threadTrends
) {
    /** 每个 dump 的摘要 */
    public record SnapshotSummary(
        int index,
        String timestamp,           // 可能为 null
        int totalThreads,
        Map<ThreadState, Long> stateDistribution
    ) {}

    /** 在所有 dump 中栈完全不动的线程 */
    public record StuckThread(
        String threadName,
        ThreadState state,          // 最后一个 dump 中的状态
        int dumpsSeen,
        String topMethod,
        List<String> topFrames      // 栈顶若干帧，方便直接定位
    ) {}

    /** 首末 dump 之间的 CPU 时间增量 */
    public record CpuDelta(
        String threadName,
        double cpuMsDelta,          // 首末差值 (ms)
        double firstCpuMs,
        double lastCpuMs,
        ThreadState lastState,
        String topMethod
    ) {}

    /** 线程组数量趋势 (按名称前缀聚合) */
    public record ThreadTrend(
        String groupName,
        List<Integer> counts        // 每个 dump 中的数量，与 snapshots 顺序对应
    ) {}
}
