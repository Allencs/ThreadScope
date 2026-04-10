package com.threadscope.model;

import java.util.List;
import java.util.Map;

/**
 * 线程池信息 — 自动识别聚合的线程池数据。
 */
public record ThreadPoolInfo(
    String poolName,
    String poolType,            // "Tomcat", "HikariCP", "Dubbo", "ForkJoinPool", "Scheduler", "Custom"
    int totalThreads,
    Map<ThreadState, Integer> stateDistribution,
    List<String> threadNames
) {
    public int activeCount() {
        return stateDistribution.getOrDefault(ThreadState.RUNNABLE, 0);
    }

    public int blockedCount() {
        return stateDistribution.getOrDefault(ThreadState.BLOCKED, 0);
    }

    public int waitingCount() {
        return stateDistribution.getOrDefault(ThreadState.WAITING, 0)
             + stateDistribution.getOrDefault(ThreadState.TIMED_WAITING, 0);
    }

    public double utilizationRate() {
        return totalThreads > 0 ? (double) activeCount() / totalThreads * 100 : 0;
    }
}
