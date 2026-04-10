package com.threadscope.engine.analyzer;

import com.threadscope.model.*;
import com.threadscope.model.HealthReport.HealthLevel;
import com.threadscope.model.HealthReport.RiskItem;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 健康检查引擎 — 基于专家经验规则对 Thread Dump 进行自动化风险评估。
 *
 * 检测规则来源:
 * - 多年JVM调优实战经验
 * - 常见生产故障模式
 * - 性能基线阈值
 */
public class HealthChecker {

    // ── 阈值常量 ──
    private static final double BLOCKED_RATIO_WARNING = 0.05;   // BLOCKED > 5% 告警
    private static final double BLOCKED_RATIO_CRITICAL = 0.15;  // BLOCKED > 15% 严重
    private static final int BLOCK_STORM_THRESHOLD = 5;          // 同一锁阻塞 ≥ 5 个线程
    private static final double POOL_EXHAUSTION_THRESHOLD = 0.90; // 线程池利用率 > 90%

    /**
     * 执行全面健康检查。
     */
    public HealthReport check(
            List<ThreadInfo> threads,
            DeadlockInfo deadlocks,
            List<LockInfo> lockInfos,
            List<ThreadPoolInfo> threadPools) {

        List<RiskItem> risks = new ArrayList<>();

        // ── 检测1: 死锁 ──
        checkDeadlocks(deadlocks, risks);

        // ── 检测2: 阻塞风暴 (大量线程被同一锁阻塞) ──
        checkBlockStorm(lockInfos, risks);

        // ── 检测3: BLOCKED线程比例异常 ──
        checkBlockedRatio(threads, risks);

        // ── 检测4: 线程池耗尽 ──
        checkPoolExhaustion(threadPools, risks);

        // ── 检测5: 疑似CPU死循环 ──
        checkCpuLoopSuspect(threads, risks);

        // ── 计算总体健康等级 ──
        HealthLevel overallLevel = risks.stream()
            .map(RiskItem::level)
            .max(Comparator.comparingInt(HealthLevel::ordinal))
            .orElse(HealthLevel.HEALTHY);

        return new HealthReport(overallLevel, risks);
    }

    private void checkDeadlocks(DeadlockInfo deadlocks, List<RiskItem> risks) {
        if (deadlocks != null && deadlocks.hasDeadlocks()) {
            List<String> affected = deadlocks.chains().stream()
                .flatMap(c -> c.threadNames().stream())
                .distinct()
                .toList();

            risks.add(new RiskItem(
                "DEADLOCK",
                HealthLevel.CRITICAL,
                "检测到 " + deadlocks.totalDeadlocks() + " 个死锁",
                "线程之间互相持有对方需要的锁，形成循环等待。这将导致相关线程永久阻塞，需要立即处理。",
                affected
            ));
        }
    }

    private void checkBlockStorm(List<LockInfo> lockInfos, List<RiskItem> risks) {
        if (lockInfos == null) return;

        for (LockInfo lock : lockInfos) {
            // 仅统计真正因 synchronized 竞争而 BLOCKED 的线程 (waitingToLock)，
            // 排除 parkingToWaitFor / waitingOn 产生的等待者，因为它们是正常的异步等待行为。
            long blockedWaiters = lock.blockedWaiterCount();
            if (blockedWaiters >= BLOCK_STORM_THRESHOLD) {
                risks.add(new RiskItem(
                    "BLOCK_STORM",
                    HealthLevel.WARNING,
                    blockedWaiters + " 个线程因同一锁阻塞 (BLOCKED)",
                    "锁 " + lock.lockAddress() + " (" + lock.lockClassName() + ") 被 " +
                    lock.holderThreadName() + " 持有，导致 " + blockedWaiters +
                    " 个线程处于 BLOCKED 状态。这通常表示该代码段存在严重的 synchronized 锁竞争。",
                    lock.blockedWaiterNames()
                ));
            }
        }
    }

    private void checkBlockedRatio(List<ThreadInfo> threads, List<RiskItem> risks) {
        if (threads.isEmpty()) return;

        long blockedCount = threads.stream().filter(t -> t.state() == ThreadState.BLOCKED).count();
        double ratio = (double) blockedCount / threads.size();

        if (ratio >= BLOCKED_RATIO_CRITICAL) {
            risks.add(new RiskItem(
                "HIGH_BLOCKED_RATIO",
                HealthLevel.CRITICAL,
                String.format("%.1f%% 的线程处于 BLOCKED 状态", ratio * 100),
                "大量线程被阻塞，系统吞吐量可能严重下降。请检查锁竞争和IO瓶颈。",
                threads.stream().filter(t -> t.state() == ThreadState.BLOCKED)
                       .map(ThreadInfo::name).limit(10).toList()
            ));
        } else if (ratio >= BLOCKED_RATIO_WARNING) {
            risks.add(new RiskItem(
                "HIGH_BLOCKED_RATIO",
                HealthLevel.WARNING,
                String.format("%.1f%% 的线程处于 BLOCKED 状态", ratio * 100),
                "BLOCKED线程比例偏高，建议关注锁竞争情况。",
                threads.stream().filter(t -> t.state() == ThreadState.BLOCKED)
                       .map(ThreadInfo::name).limit(10).toList()
            ));
        }
    }

    private void checkPoolExhaustion(List<ThreadPoolInfo> pools, List<RiskItem> risks) {
        if (pools == null) return;

        for (ThreadPoolInfo pool : pools) {
            // 跳过 JVM 内部池和 GC 线程
            if ("JVM-Internal".equals(pool.poolType()) || "GC".equals(pool.poolType()) ||
                "G1-GC".equals(pool.poolType()) || "JIT-Compiler".equals(pool.poolType())) {
                continue;
            }

            double utilization = pool.utilizationRate();
            if (pool.totalThreads() >= 5 && utilization >= POOL_EXHAUSTION_THRESHOLD * 100) {
                risks.add(new RiskItem(
                    "POOL_EXHAUSTION",
                    HealthLevel.WARNING,
                    "线程池 " + pool.poolName() + " 接近耗尽",
                    String.format("线程池 '%s' (%s) 共 %d 个线程，其中 %d 个活跃(%.0f%%)。池接近饱和可能导致请求排队。",
                        pool.poolName(), pool.poolType(), pool.totalThreads(),
                        pool.activeCount(), utilization),
                    pool.threadNames().stream().limit(5).toList()
                ));
            }
        }
    }

    private void checkCpuLoopSuspect(List<ThreadInfo> threads, List<RiskItem> risks) {
        // RUNNABLE 且栈顶是用户代码 (非JDK/非IO) 的线程，可能在做CPU密集计算或死循环
        List<ThreadInfo> suspects = threads.stream()
            .filter(t -> t.state() == ThreadState.RUNNABLE)
            .filter(t -> !t.stackTrace().isEmpty())
            .filter(t -> {
                StackFrame top = t.stackTrace().getFirst();
                return !top.isJdkFrame() && !top.isNative();
            })
            .toList();

        // 如果大量 RUNNABLE 线程在同一个用户方法上，可能有CPU热点
        Map<String, List<ThreadInfo>> byTopMethod = suspects.stream()
            .collect(Collectors.groupingBy(ThreadInfo::topMethod));

        byTopMethod.forEach((method, group) -> {
            if (group.size() >= 3) {
                risks.add(new RiskItem(
                    "CPU_HOTSPOT",
                    HealthLevel.WARNING,
                    group.size() + " 个 RUNNABLE 线程在同一方法",
                    "方法 " + method + " 有 " + group.size() + " 个线程在执行，可能是CPU热点。" +
                    "结合 top -H 输出确认是否占用过高CPU。",
                    group.stream().map(ThreadInfo::name).limit(5).toList()
                ));
            }
        });
    }
}
