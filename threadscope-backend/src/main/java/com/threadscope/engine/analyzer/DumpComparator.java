package com.threadscope.engine.analyzer;

import com.threadscope.model.DumpComparison;
import com.threadscope.model.DumpComparison.*;
import com.threadscope.model.StackFrame;
import com.threadscope.model.ThreadInfo;
import com.threadscope.model.ThreadState;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 多 Dump 差分分析器。
 *
 * 输入按时间顺序排列的多份 dump 的线程列表，产出：
 * 1. 栈不动检测 — 同一线程在所有 dump 中栈指纹完全一致 (排除正常的池内空闲等待)
 * 2. CPU 差值   — 首末 dump 的 cpu= 时间差，定位真实的 CPU 消耗者
 * 3. 线程趋势   — 按名称前缀聚合的数量变化，暴露线程泄漏
 */
public class DumpComparator {

    private static final int TOP_CPU_LIMIT = 15;
    private static final int STUCK_TOP_FRAMES = 5;
    private static final int TREND_MIN_VARIATION = 3;   // 数量波动小于该值的组不展示
    private static final int TREND_LIMIT = 20;

    /** 池内空闲等待的典型栈顶 — 这些线程栈天然不动，不算"卡死" */
    private static final Set<String> IDLE_TOP_METHODS = Set.of(
        "jdk.internal.misc.Unsafe.park",
        "sun.misc.Unsafe.park",
        "java.lang.Object.wait",
        "java.lang.Object.wait0",
        "java.lang.Thread.sleep",
        "java.lang.Thread.sleep0",
        "java.lang.Thread.sleepNanos0",
        "java.lang.VirtualThread.takeVirtualThreadListToUnblock",
        // accept 循环 — 等新连接是常态
        "sun.nio.ch.Net.accept",
        "sun.nio.ch.ServerSocketChannelImpl.accept",
        "java.net.PlainSocketImpl.socketAccept",
        "sun.nio.ch.EPollArrayWrapper.epollWait",
        "sun.nio.ch.EPoll.wait",
        "sun.nio.ch.KQueue.poll",
        "sun.nio.ch.WindowsSelectorImpl$SubSelector.poll0",
        "java.lang.ref.Reference.waitForReferencePendingList",
        "java.lang.ProcessHandleImpl.waitForProcessExit0",
        // Netty 事件循环 (epoll / kqueue / io_uring) — 等事件是常态
        "io.netty.channel.epoll.Native.epollWait",
        "io.netty.channel.epoll.Native.epollWait0",
        "io.netty.channel.kqueue.Native.keventWait",
        "io.netty.channel.uring.Native.ioUringEnter"
    );

    /**
     * 对比多份 dump。
     *
     * @param dumps      每份 dump 的线程列表，按抓取时间升序
     * @param timestamps 每份 dump 的时间戳 (元素可为 null)
     */
    public DumpComparison compare(List<List<ThreadInfo>> dumps, List<String> timestamps) {
        List<SnapshotSummary> snapshots = new ArrayList<>();
        for (int i = 0; i < dumps.size(); i++) {
            List<ThreadInfo> threads = dumps.get(i);
            snapshots.add(new SnapshotSummary(
                i,
                i < timestamps.size() ? timestamps.get(i) : null,
                threads.size(),
                threads.stream().collect(Collectors.groupingBy(ThreadInfo::state, Collectors.counting()))
            ));
        }

        return new DumpComparison(
            dumps.size(),
            snapshots,
            findStuckThreads(dumps),
            computeCpuDeltas(dumps),
            computeThreadTrends(dumps)
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  栈不动检测
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private List<StuckThread> findStuckThreads(List<List<ThreadInfo>> dumps) {
        List<ThreadInfo> first = dumps.getFirst();
        List<Map<String, ThreadInfo>> maps = dumps.stream()
            .map(list -> list.stream().collect(
                Collectors.toMap(ThreadInfo::name, t -> t, (a, b) -> a)))
            .toList();

        List<StuckThread> stuck = new ArrayList<>();
        for (ThreadInfo thread : first) {
            if (thread.stackTrace().size() < 3) continue;   // 浅栈没有指纹意义
            if (isIdleParked(thread)) continue;             // 池内正常空闲

            String fingerprint = thread.stackFingerprint();
            boolean inAllAndUnchanged = true;
            ThreadInfo last = thread;
            for (int i = 1; i < maps.size(); i++) {
                ThreadInfo other = maps.get(i).get(thread.name());
                if (other == null || !fingerprint.equals(other.stackFingerprint())) {
                    inAllAndUnchanged = false;
                    break;
                }
                last = other;
            }

            if (inAllAndUnchanged) {
                stuck.add(new StuckThread(
                    thread.name(),
                    last.state(),
                    dumps.size(),
                    last.topMethod(),
                    last.stackTrace().stream()
                        .limit(STUCK_TOP_FRAMES)
                        .map(StackFrame::fullMethod)
                        .toList()
                ));
            }
        }

        // BLOCKED 的最可疑，其次 RUNNABLE (可能死循环在同一处)
        stuck.sort(Comparator.comparing((StuckThread s) -> s.state() != ThreadState.BLOCKED)
            .thenComparing(s -> s.state() != ThreadState.RUNNABLE));
        return stuck;
    }

    /** 判断线程是否处于池内空闲等待 (栈顶是 park/wait/epoll 等且没有等锁) */
    private boolean isIdleParked(ThreadInfo thread) {
        if (thread.state() == ThreadState.BLOCKED) return false;   // BLOCKED 永远值得关注
        String top = normalize(thread.topMethod());
        return IDLE_TOP_METHODS.contains(top);
    }

    private static String normalize(String fullMethod) {
        int slash = fullMethod.indexOf('/');
        return slash >= 0 ? fullMethod.substring(slash + 1) : fullMethod;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  CPU 差值
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private List<CpuDelta> computeCpuDeltas(List<List<ThreadInfo>> dumps) {
        Map<String, ThreadInfo> firstMap = dumps.getFirst().stream()
            .collect(Collectors.toMap(ThreadInfo::name, t -> t, (a, b) -> a));
        // 同名线程都取"首个出现"，保证首末快照对齐 (重名线程无法可靠配对)
        Map<String, ThreadInfo> lastMap = dumps.getLast().stream()
            .collect(Collectors.toMap(ThreadInfo::name, t -> t, (a, b) -> a));

        List<CpuDelta> deltas = new ArrayList<>();
        for (ThreadInfo last : lastMap.values()) {
            ThreadInfo firstThread = firstMap.get(last.name());
            if (firstThread == null) continue;

            double firstCpu = parseCpuMs(firstThread.cpuTime());
            double lastCpu = parseCpuMs(last.cpuTime());
            if (firstCpu < 0 || lastCpu < 0) continue;

            double delta = lastCpu - firstCpu;
            if (delta > 0) {
                deltas.add(new CpuDelta(
                    last.name(), delta, firstCpu, lastCpu, last.state(), last.topMethod()));
            }
        }

        deltas.sort(Comparator.comparingDouble(CpuDelta::cpuMsDelta).reversed());
        return deltas.stream().limit(TOP_CPU_LIMIT).toList();
    }

    /**
     * 解析 cpu 字符串: "125.40ms" → 125.40；"1.5s" → 1500。无法解析返回 -1。
     */
    static double parseCpuMs(String cpuTime) {
        if (cpuTime == null || cpuTime.isBlank()) return -1;
        try {
            if (cpuTime.endsWith("ms")) {
                return Double.parseDouble(cpuTime.substring(0, cpuTime.length() - 2));
            }
            if (cpuTime.endsWith("s")) {
                return Double.parseDouble(cpuTime.substring(0, cpuTime.length() - 1)) * 1000;
            }
            return Double.parseDouble(cpuTime);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  线程数趋势
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private List<ThreadTrend> computeThreadTrends(List<List<ThreadInfo>> dumps) {
        // 每个 dump 内按前缀聚合计数
        List<Map<String, Integer>> perDumpCounts = dumps.stream()
            .map(threads -> {
                Map<String, Integer> counts = new HashMap<>();
                for (ThreadInfo t : threads) {
                    counts.merge(groupName(t.name()), 1, Integer::sum);
                }
                return counts;
            })
            .toList();

        Set<String> allGroups = perDumpCounts.stream()
            .flatMap(m -> m.keySet().stream())
            .collect(Collectors.toSet());

        List<ThreadTrend> trends = new ArrayList<>();
        for (String group : allGroups) {
            List<Integer> counts = perDumpCounts.stream()
                .map(m -> m.getOrDefault(group, 0))
                .toList();
            int min = counts.stream().min(Integer::compare).orElse(0);
            int max = counts.stream().max(Integer::compare).orElse(0);
            if (max - min >= TREND_MIN_VARIATION) {
                trends.add(new ThreadTrend(group, counts));
            }
        }

        // 按波动幅度降序
        trends.sort(Comparator.comparingInt((ThreadTrend t) -> {
            int min = t.counts().stream().min(Integer::compare).orElse(0);
            int max = t.counts().stream().max(Integer::compare).orElse(0);
            return max - min;
        }).reversed());
        return trends.stream().limit(TREND_LIMIT).toList();
    }

    /** "http-nio-8080-exec-12" → "http-nio-8080-exec"；无数字后缀的线程名原样返回 */
    private String groupName(String threadName) {
        String prefix = threadName.replaceAll("[\\-_#]?\\d+$", "");
        return prefix.isBlank() ? threadName : prefix;
    }
}
