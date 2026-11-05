package com.threadscope.model;

import java.util.List;
import java.util.Map;

/**
 * 线程池信息 — 自动识别聚合的线程池数据。
 *
 * @param poolName          池名 (线程名前缀)
 * @param poolType          具体框架/实现，如 "Tomcat"、"Undertow"、"Quartz"
 * @param category          业务大类，如 "WEB"、"RPC"、"SCHEDULER"，见 ThreadPoolDetector
 * @param totalThreads      线程数
 * @param stateDistribution 状态分布
 * @param threadNames       池内线程名 (可能重名)
 * @param threadKeys        池内线程唯一标识，与 threadNames 一一对应
 * @param idleCount         按栈判定为"等新任务"的空闲线程数
 * @param busyCount         正在执行任务或在任务中等待资源的线程数
 * @param hints             治理建议 (如匿名线程池命名规范)
 */
public record ThreadPoolInfo(
    String poolName,
    String poolType,
    String category,
    int totalThreads,
    Map<ThreadState, Integer> stateDistribution,
    List<String> threadNames,
    List<String> threadKeys,
    int idleCount,
    int busyCount,
    List<String> hints
) {
    public int activeCount() {
        return stateDistribution.getOrDefault(ThreadState.RUNNABLE, 0);
    }

    public int blockedCount() {
        return stateDistribution.getOrDefault(ThreadState.BLOCKED, 0);
    }

    /** 利用率 = 忙碌线程比例 (百分比)。 */
    public double utilizationRate() {
        return totalThreads > 0 ? (double) busyCount / totalThreads * 100 : 0;
    }
}
