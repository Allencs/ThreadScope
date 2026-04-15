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
                    blockedWaiters + " 个线程因同一把锁阻塞 (BLOCKED)",
                    "锁 " + lock.lockAddress() + " (" + lock.lockClassName() + ") 目前由线程 \"" +
                    lock.holderThreadName() + "\" 持有，导致高达 " + blockedWaiters +
                    " 个其他线程在尝试获取该监视器锁时被阻塞。这通常表示相关代码段存在严重的 synchronized 锁竞争，可能成为系统的性能瓶颈。",
                    lock.blockedWaiterNames()
                ));
            }
        }
    }

    private void checkBlockedRatio(List<ThreadInfo> threads, List<RiskItem> risks) {
        if (threads.isEmpty()) return;

        List<ThreadInfo> blockedThreads = threads.stream()
            .filter(t -> t.state() == ThreadState.BLOCKED)
            .toList();
        long blockedCount = blockedThreads.size();
        if (blockedCount == 0) return;

        double ratio = (double) blockedCount / threads.size();

        if (ratio >= BLOCKED_RATIO_CRITICAL) {
            risks.add(new RiskItem(
                "HIGH_BLOCKED_RATIO",
                HealthLevel.CRITICAL,
                String.format("%.1f%% 的线程处于 BLOCKED 状态", ratio * 100),
                "系统中高达 " + String.format("%.1f%%", ratio * 100) + " 的线程被阻塞。大量线程处于 BLOCKED 状态将导致系统吞吐量严重下降，请优先排查可能存在的大范围热点锁竞争、底层 I/O 阻塞或连接池耗尽等瓶颈。",
                blockedThreads.stream().map(ThreadInfo::name).limit(10).toList()
            ));
        } else if (ratio >= BLOCKED_RATIO_WARNING) {
            risks.add(new RiskItem(
                "HIGH_BLOCKED_RATIO",
                HealthLevel.WARNING,
                String.format("%.1f%% 的线程处于 BLOCKED 状态", ratio * 100),
                "系统中处于 BLOCKED 状态的线程比例偏高 (" + String.format("%.1f%%", ratio * 100) + ")。这通常意味着存在较大范围的锁等待，建议关注热点对象的并发竞争或外部资源的调用耗时。",
                blockedThreads.stream().map(ThreadInfo::name).limit(10).toList()
            ));
        } else {
            String desc = blockedCount == 1
                ? "线程 \"" + blockedThreads.getFirst().name() + "\" 当前为 BLOCKED 状态。" +
                  "这通常是因为该线程正在等待获取 synchronized 监视器锁，或者是调用 Object.wait() 被唤醒后正在重新竞争锁资源。" +
                  "在瞬态快照中，极少量的 BLOCKED 属于正常竞争，如果该线程长时间处于此状态，需排查锁持有者的耗时操作。"
                : "当前有 " + blockedCount + " 个线程处于 BLOCKED 状态，占总线程数的 " + String.format("%.1f%%", ratio * 100) + "。" +
                  "这表明系统存在一定程度的 synchronized 锁竞争，或部分线程从 wait() 状态唤醒后正在重新获取锁。" +
                  "由于占比处于较低水平，暂不构成系统性风险，建议结合业务场景持续观察。";
            risks.add(new RiskItem(
                "BLOCKED_THREADS",
                HealthLevel.INFO,
                blockedCount + " 个线程处于 BLOCKED 状态",
                desc,
                blockedThreads.stream().map(ThreadInfo::name).limit(10).toList()
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
