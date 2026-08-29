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

    /**
     * 非空闲线程数。池中 WAITING/TIMED_WAITING 的 worker 通常在 take()/poll() 等任务，
     * 属于空闲；RUNNABLE 与 BLOCKED (等锁) 的线程都无法接收新任务。
     */
    public int busyCount() {
        return Math.max(0, totalThreads - waitingCount());
    }

    /**
     * 利用率 = 非空闲线程比例。
     * 注意不能用 RUNNABLE/total：池饱和时大量线程常因等 DB/锁而处于 BLOCKED，
     * 若只统计 RUNNABLE 会在最需要告警的时候漏报。
     */
    public double utilizationRate() {
        return totalThreads > 0 ? (double) busyCount() / totalThreads * 100 : 0;
    }
}
