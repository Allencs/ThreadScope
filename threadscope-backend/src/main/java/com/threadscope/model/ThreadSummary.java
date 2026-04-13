package com.threadscope.model;

/**
 * 线程轻量摘要 — 仅包含列表展示所需的最小字段集。
 * 相比完整 ThreadInfo 去掉了 stackTrace / lockActions / ownableSynchronizers 等大字段，
 * 用于锁分析页批量预加载等待线程列表信息，显著减少网络传输量。
 */
public record ThreadSummary(
    String name,
    ThreadState state,
    boolean daemon,
    int stackDepth,
    boolean hasLockActions
) {
    public static ThreadSummary from(ThreadInfo info) {
        return new ThreadSummary(
            info.name(),
            info.state(),
            info.daemon(),
            info.stackTrace() != null ? info.stackTrace().size() : 0,
            info.lockActions() != null && !info.lockActions().isEmpty()
        );
    }
}
