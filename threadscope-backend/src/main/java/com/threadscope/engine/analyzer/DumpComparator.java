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
 *
 * 跨 dump 配对用 "线程名 + 线程标识"：
 * - 只用线程名会把重名线程 (自建池里的同名 worker) 配错
 * - Java 线程 ID (#N) 同一 JVM 内唯一且不复用，但 JVM 重启后会从头分配，需结合存活时间识别重启
 */
public class DumpComparator {

    private static final int TOP_CPU_LIMIT = 15;
    private static final int STUCK_TOP_FRAMES = 5;
    private static final int TREND_MIN_VARIATION = 3;   // 数量波动小于该值的组不展示
    private static final int TREND_LIMIT = 20;
    /** 配对线程中存活时间变短的比例超过该值 → 判定 JVM 重启 */
    private static final double RESTART_RATIO = 0.5;

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

        List<Map<String, ThreadInfo>> maps = dumps.stream().map(DumpComparator::indexByMatchKey).toList();
        boolean restarted = detectRestart(maps);
        int matched = (int) maps.getFirst().keySet().stream().filter(maps.getLast()::containsKey).count();

        return new DumpComparison(
            dumps.size(),
            snapshots,
            restarted ? List.of() : findStuckThreads(dumps.getFirst(), maps),
            restarted ? List.of() : computeCpuDeltas(maps.getFirst(), maps.getLast()),
            computeThreadTrends(dumps),
            restarted,
            restarted ? 0 : matched
        );
    }

    /** 跨 dump 配对键 */
    static String matchKey(ThreadInfo t) {
        return t.name() + "|" + t.key();
    }

    private static Map<String, ThreadInfo> indexByMatchKey(List<ThreadInfo> threads) {
        return threads.stream().collect(Collectors.toMap(DumpComparator::matchKey, t -> t, (a, b) -> a));
    }

    /**
     * JVM 重启检测：配对上的线程，若后一份 dump 中存活时间 (elapsed) 明显变短，说明不是同一个线程。
     * 没有 elapsed 字段的 dump (JDK 8) 无法判断，返回 false。
     */
    private static boolean detectRestart(List<Map<String, ThreadInfo>> maps) {
        for (int i = 1; i < maps.size(); i++) {
            Map<String, ThreadInfo> prev = maps.get(i - 1);
            Map<String, ThreadInfo> cur = maps.get(i);
            int compared = 0;
            int shrunk = 0;
            for (var e : prev.entrySet()) {
                ThreadInfo after = cur.get(e.getKey());
                if (after == null) continue;
                double before = HealthChecker.parseElapsedSeconds(e.getValue().elapsed());
                double now = HealthChecker.parseElapsedSeconds(after.elapsed());
                if (before < 0 || now < 0) continue;
                compared++;
                if (now + 1.0 < before) shrunk++;
            }
            if (compared > 0 && (double) shrunk / compared > RESTART_RATIO) return true;
            // 一个线程都配不上而两边都有线程，同样视为重启 (所有 Java 线程 ID 都变了)
            if (!prev.isEmpty() && !cur.isEmpty() && prev.keySet().stream().noneMatch(cur::containsKey)) return true;
        }
        return false;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  栈不动检测
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private List<StuckThread> findStuckThreads(List<ThreadInfo> first, List<Map<String, ThreadInfo>> maps) {
        List<StuckThread> stuck = new ArrayList<>();
        for (ThreadInfo thread : first) {
            if (thread.stackTrace().size() < 3) continue;   // 浅栈没有指纹意义
            if (isIdleParked(thread)) continue;             // 池内正常空闲

            String key = matchKey(thread);
            String fingerprint = thread.stackFingerprint();
            boolean inAllAndUnchanged = true;
            ThreadInfo last = thread;
            for (int i = 1; i < maps.size(); i++) {
                ThreadInfo other = maps.get(i).get(key);
                if (other == null || !fingerprint.equals(other.stackFingerprint())) {
                    inAllAndUnchanged = false;
                    break;
                }
                last = other;
            }

            if (inAllAndUnchanged) {
                stuck.add(new StuckThread(
                    thread.name(),
                    last.key(),
                    last.state(),
                    maps.size(),
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

    /**
     * 判断线程是否处于空闲等待 (等任务 / 等事件)。BLOCKED 永远值得关注；
     * 在任务中等资源 (getConnection、Future.get) 且栈一直不动的线程不算空闲 — 它们正是要找的卡点。
     */
    private boolean isIdleParked(ThreadInfo thread) {
        return FrameClassifier.isIdleWorker(thread);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  CPU 差值
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private List<CpuDelta> computeCpuDeltas(Map<String, ThreadInfo> firstMap, Map<String, ThreadInfo> lastMap) {
        List<CpuDelta> deltas = new ArrayList<>();
        for (var e : lastMap.entrySet()) {
            ThreadInfo last = e.getValue();
            ThreadInfo firstThread = firstMap.get(e.getKey());
            if (firstThread == null) continue;

            double firstCpu = parseCpuMs(firstThread.cpuTime());
            double lastCpu = parseCpuMs(last.cpuTime());
            if (firstCpu < 0 || lastCpu < 0) continue;

            double delta = lastCpu - firstCpu;
            if (delta > 0) {
                deltas.add(new CpuDelta(
                    last.name(), last.key(), delta, firstCpu, lastCpu, last.state(), last.topMethod()));
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
