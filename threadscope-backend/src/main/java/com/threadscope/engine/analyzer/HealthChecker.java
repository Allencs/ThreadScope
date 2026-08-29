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
    private static final int THREAD_COUNT_WARNING = 1000;        // 线程总数告警线
    private static final int THREAD_COUNT_CRITICAL = 3000;       // 线程总数严重线
    private static final int NON_DAEMON_WARNING = 200;           // 非 daemon 线程告警线
    private static final int DEEP_STACK_FRAMES = 500;            // 栈深度告警线 (递归嫌疑)
    private static final int IO_BOTTLENECK_THRESHOLD = 10;       // 同一 IO 点等待线程数
    private static final int ROOT_BLOCKER_MIN = 5;               // 根阻塞报告的最小拖累数
    private static final double CHURN_ELAPSED_SECONDS = 60;      // "新生线程"的存活阈值
    private static final int CHURN_MIN_COUNT = 50;               // 线程频繁创建的最小数量

    private final KnownIssueDetector knownIssueDetector = new KnownIssueDetector();
    private final BlockChainAnalyzer blockChainAnalyzer = new BlockChainAnalyzer();

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

        // ── 检测2: 锁等待链根因 (传递阻塞的元凶线程) ──
        checkRootBlockers(threads, risks);

        // ── 检测3: 阻塞风暴 (大量线程被同一锁阻塞) ──
        checkBlockStorm(lockInfos, risks);

        // ── 检测4: BLOCKED线程比例异常 ──
        checkBlockedRatio(threads, risks);

        // ── 检测5: 线程池耗尽 ──
        checkPoolExhaustion(threadPools, risks);

        // ── 检测6: 疑似CPU死循环 ──
        checkCpuLoopSuspect(threads, risks);

        // ── 检测7: IO 瓶颈 (大量 RUNNABLE 线程实际在等 IO) ──
        checkIoBottleneck(threads, risks);

        // ── 检测8: 已知病症指纹库 ──
        risks.addAll(knownIssueDetector.detect(threads));

        // ── 检测9: 线程总量与构成 ──
        checkThreadCount(threads, risks);
        checkNonDaemonThreads(threads, risks);
        checkThreadChurn(threads, risks);

        // ── 检测10: Finalizer 阻塞 (OOM 前兆) ──
        checkFinalizerBlocked(threads, risks);

        // ── 检测11: 异常深栈 (递归风险) ──
        checkDeepStacks(threads, risks);

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
                    String.format("线程池 '%s' (%s) 共 %d 个线程，其中 %d 个非空闲(%.0f%%，RUNNABLE %d 个 / BLOCKED %d 个)。池接近饱和可能导致请求排队。",
                        pool.poolName(), pool.poolType(), pool.totalThreads(),
                        pool.busyCount(), utilization, pool.activeCount(), pool.blockedCount()),
                    pool.threadNames().stream().limit(5).toList()
                ));
            }
        }
    }

    /**
     * 锁等待链根因定位 — 把 N 条锁记录收敛到"1 个元凶线程"。
     */
    private void checkRootBlockers(List<ThreadInfo> threads, List<RiskItem> risks) {
        Map<String, ThreadInfo> byName = threads.stream()
            .collect(Collectors.toMap(ThreadInfo::name, t -> t, (a, b) -> a));

        for (var root : blockChainAnalyzer.findRootBlockers(threads)) {
            if (root.totalBlocked() < ROOT_BLOCKER_MIN) continue;

            ThreadInfo rootThread = byName.get(root.rootThreadName());
            String rootActivity = rootThread != null
                ? "该线程当前状态 " + rootThread.state() + "，栈顶: " + rootThread.topMethod()
                : "该线程信息缺失";

            HealthLevel level = root.totalBlocked() >= 20 ? HealthLevel.CRITICAL : HealthLevel.WARNING;
            List<String> affected = new ArrayList<>();
            affected.add(root.rootThreadName());
            affected.addAll(root.blockedSamples());

            risks.add(new RiskItem(
                "ROOT_BLOCKER",
                level,
                "线程 \"" + root.rootThreadName() + "\" 拖住了 " + root.totalBlocked() + " 个线程",
                "该线程持有的锁直接阻塞 " + root.directBlocked() + " 个线程、经等待链传递共拖住 " +
                root.totalBlocked() + " 个线程 (最长链深 " + root.maxChainDepth() + ")。" +
                rootActivity + "。它是这批阻塞的根因 — 优先排查它为何长时间持锁。",
                affected
            ));
        }
    }

    /**
     * IO 瓶颈 — RUNNABLE 但实际阻塞在网络/磁盘 IO 上的线程扎堆。
     */
    private void checkIoBottleneck(List<ThreadInfo> threads, List<RiskItem> risks) {
        Map<String, List<ThreadInfo>> byBusinessFrame = threads.stream()
            .filter(FrameClassifier::isIoBoundRunnable)
            .collect(Collectors.groupingBy(this::firstBusinessMethod));

        byBusinessFrame.forEach((method, group) -> {
            if (group.size() >= IO_BOTTLENECK_THRESHOLD) {
                risks.add(new RiskItem(
                    "IO_BOTTLENECK",
                    HealthLevel.WARNING,
                    group.size() + " 个 RUNNABLE 线程实际在等待 IO",
                    "这些线程虽显示为 RUNNABLE，但栈顶停在 native socket/文件读写上 — 真实瓶颈是下游响应慢或磁盘 IO，" +
                    "而不是本机 CPU。共同的业务调用点: " + method + "。建议排查对应下游服务的耗时。",
                    group.stream().map(ThreadInfo::name).limit(10).toList()
                ));
            }
        });
    }

    /** 从栈中找第一个非 JDK 帧作为"业务调用点"，用于聚合 IO 等待线程 */
    private String firstBusinessMethod(ThreadInfo thread) {
        return thread.stackTrace().stream()
            .filter(f -> !f.isJdkFrame())
            .findFirst()
            .map(StackFrame::fullMethod)
            .orElse(thread.topMethod());
    }

    private void checkThreadCount(List<ThreadInfo> threads, List<RiskItem> risks) {
        int total = threads.size();
        if (total >= THREAD_COUNT_CRITICAL) {
            risks.add(new RiskItem(
                "THREAD_COUNT", HealthLevel.CRITICAL,
                "线程总数高达 " + total,
                "线程数超过 " + THREAD_COUNT_CRITICAL + "，极可能存在线程泄漏（每请求建线程、线程池无上限等）。" +
                "过多线程本身会消耗大量栈内存 (约 1MB/线程) 并加剧上下文切换。建议按线程名前缀定位增长最快的来源。",
                List.of()
            ));
        } else if (total >= THREAD_COUNT_WARNING) {
            risks.add(new RiskItem(
                "THREAD_COUNT", HealthLevel.WARNING,
                "线程总数偏高 (" + total + ")",
                "线程数超过 " + THREAD_COUNT_WARNING + "。如果业务规模不需要这么多并发，" +
                "请检查是否存在未复用的线程创建或过大的线程池配置。",
                List.of()
            ));
        }
    }

    private void checkNonDaemonThreads(List<ThreadInfo> threads, List<RiskItem> risks) {
        List<ThreadInfo> nonDaemon = threads.stream()
            .filter(t -> !t.daemon())
            .filter(t -> !"main".equals(t.name()) && !"DestroyJavaVM".equals(t.name()))
            .toList();

        if (nonDaemon.size() >= NON_DAEMON_WARNING) {
            risks.add(new RiskItem(
                "NON_DAEMON_THREADS", HealthLevel.WARNING,
                nonDaemon.size() + " 个非 daemon 用户线程",
                "大量非 daemon 线程会阻止 JVM 正常退出（优雅停机会被卡住）。" +
                "自建线程池建议设置 daemon 或注册关闭钩子确保停机时线程能退出。",
                nonDaemon.stream().map(ThreadInfo::name).limit(10).toList()
            ));
        }
    }

    /**
     * 线程频繁创建 (churn) — 大量存活时间很短的线程说明没有复用线程池。
     */
    private void checkThreadChurn(List<ThreadInfo> threads, List<RiskItem> risks) {
        List<ThreadInfo> youngThreads = threads.stream()
            .filter(t -> {
                double sec = parseElapsedSeconds(t.elapsed());
                return sec >= 0 && sec < CHURN_ELAPSED_SECONDS;
            })
            .toList();

        if (youngThreads.size() >= CHURN_MIN_COUNT &&
            youngThreads.size() >= threads.size() * 0.3) {
            risks.add(new RiskItem(
                "THREAD_CHURN", HealthLevel.WARNING,
                youngThreads.size() + " 个线程存活不足 " + (int) CHURN_ELAPSED_SECONDS + " 秒",
                "快照中 " + String.format("%.0f%%", 100.0 * youngThreads.size() / threads.size()) +
                " 的线程都是最近 1 分钟内创建的，说明可能在频繁地创建/销毁线程而非复用线程池。" +
                "频繁建线程的开销 (栈分配 + 内核调度) 在高并发下非常可观。",
                youngThreads.stream().map(ThreadInfo::name).limit(10).toList()
            ));
        }
    }

    /**
     * Finalizer 线程阻塞 — finalize 队列积压是经典的 OOM 前兆。
     */
    private void checkFinalizerBlocked(List<ThreadInfo> threads, List<RiskItem> risks) {
        threads.stream()
            .filter(t -> "Finalizer".equals(t.name()))
            .filter(t -> t.state() == ThreadState.BLOCKED ||
                        (t.state() == ThreadState.RUNNABLE && !t.stackTrace().isEmpty()
                         && !t.topMethod().contains("ReferenceQueue")))
            .findFirst()
            .ifPresent(finalizer -> risks.add(new RiskItem(
                "FINALIZER_BLOCKED", HealthLevel.WARNING,
                "Finalizer 线程未在正常等待队列",
                "Finalizer 当前状态 " + finalizer.state() + "，栈顶 " + finalizer.topMethod() +
                "。若 finalize() 执行慢或被锁阻塞，待回收对象会持续积压并最终导致 OOM。" +
                "建议排查重写了 finalize() 的类，改用 Cleaner 或 try-with-resources。",
                List.of("Finalizer")
            )));
    }

    private void checkDeepStacks(List<ThreadInfo> threads, List<RiskItem> risks) {
        List<ThreadInfo> deep = threads.stream()
            .filter(t -> t.stackTrace().size() >= DEEP_STACK_FRAMES)
            .sorted(Comparator.comparingInt((ThreadInfo t) -> t.stackTrace().size()).reversed())
            .toList();

        if (!deep.isEmpty()) {
            ThreadInfo worst = deep.getFirst();
            risks.add(new RiskItem(
                "DEEP_STACK", HealthLevel.WARNING,
                deep.size() + " 个线程栈深超过 " + DEEP_STACK_FRAMES + " 帧",
                "最深的线程 \"" + worst.name() + "\" 有 " + worst.stackTrace().size() +
                " 帧，极可能存在无终止条件的递归调用，继续加深会抛出 StackOverflowError。" +
                "检查栈中是否有重复出现的方法序列。",
                deep.stream().map(ThreadInfo::name).limit(5).toList()
            ));
        }
    }

    /**
     * 解析 elapsed 字符串: "3847.12s" → 3847.12。无法解析返回 -1。
     */
    static double parseElapsedSeconds(String elapsed) {
        if (elapsed == null || elapsed.isBlank()) return -1;
        try {
            if (elapsed.endsWith("s")) {
                return Double.parseDouble(elapsed.substring(0, elapsed.length() - 1));
            }
            return Double.parseDouble(elapsed);
        } catch (NumberFormatException e) {
            return -1;
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
