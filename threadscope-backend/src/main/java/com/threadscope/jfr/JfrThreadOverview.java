package com.threadscope.jfr;

import com.threadscope.engine.analyzer.ThreadPoolDetector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把平台线程按线程池归类，并按时间桶统计创建、销毁和存活数量。
 * 线程池名沿用线程 Dump 分析里的命名规则，匹配不上的按去掉数字后缀的前缀聚合。
 */
public final class JfrThreadOverview {

    static final String JVM_POOL = "JVM 内部线程";
    static final String STANDALONE_POOL = "独立线程";
    private static final int TARGET_BUCKETS = 60;

    private JfrThreadOverview() {}

    /** fallbackStart/fallbackEnd 在既没有 JavaThreadStatistics 也没有线程启停事件时作为时间窗口。 */
    public static JfrDocuments.ThreadOverview build(
            JfrDocuments.ThreadDocument doc, long fallbackStart, long fallbackEnd) {
        List<JfrDocuments.PlatformThread> threads = doc.platform();
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (JfrDocuments.StatPoint point : doc.statistics()) {
            start = Math.min(start, point.epochMs());
            end = Math.max(end, point.epochMs());
        }
        for (JfrDocuments.PlatformThread thread : threads) {
            if (created(thread)) {
                start = Math.min(start, thread.startEpochMs());
                end = Math.max(end, thread.startEpochMs());
            }
            if (ended(thread)) {
                start = Math.min(start, thread.endEpochMs());
                end = Math.max(end, thread.endEpochMs());
            }
        }
        if (start == Long.MAX_VALUE) {
            start = fallbackStart;
            end = fallbackEnd;
        }
        if (end <= start) end = start + 1000;

        long span = end - start;
        long bucketMillis = Math.max(1000, ceilDiv(span, TARGET_BUCKETS));
        int buckets = (int) Math.max(1, ceilDiv(span, bucketMillis));
        Window window = new Window(start, bucketMillis, buckets);

        Long[] active = new Long[buckets];
        long peak = -1;
        for (JfrDocuments.StatPoint point : doc.statistics()) {
            int index = window.index(point.epochMs());
            if (active[index] == null || point.epochMs() >= window.lastStat[index]) {
                active[index] = point.active();
                window.lastStat[index] = point.epochMs();
            }
            peak = Math.max(peak, point.peak());
        }

        List<JfrDocuments.ThreadPoolGroup> pools = new ArrayList<>();
        for (var entry : group(threads).entrySet()) {
            Group g = entry.getValue();
            pools.add(summarize(entry.getKey(), g.type, g.category, g.threads, window));
        }
        pools.sort(Comparator.comparingInt(JfrDocuments.ThreadPoolGroup::total).reversed()
            .thenComparing(JfrDocuments.ThreadPoolGroup::name));

        JfrDocuments.ThreadPoolGroup all = summarize("全部", "", "", threads, window);
        return new JfrDocuments.ThreadOverview(
            start, end, bucketMillis,
            threads.size(), all.created(), all.ended(), all.aliveAtEnd(),
            peak < 0 ? null : peak,
            all.series(),
            Arrays.asList(active),
            pools,
            doc.virtualThreads());
    }

    private static Map<String, Group> group(List<JfrDocuments.PlatformThread> threads) {
        Map<String, Group> pools = new LinkedHashMap<>();
        Map<String, List<JfrDocuments.PlatformThread>> byPrefix = new LinkedHashMap<>();
        List<JfrDocuments.PlatformThread> standalone = new ArrayList<>();
        for (JfrDocuments.PlatformThread thread : threads) {
            String name = thread.name();
            if (name == null || name.isBlank()) {
                standalone.add(thread);
                continue;
            }
            ThreadPoolDetector.PoolName known = ThreadPoolDetector.classifyName(name);
            if (known != null) {
                boolean jvm = ThreadPoolDetector.CATEGORY_JVM.equals(known.category());
                String key = jvm ? JVM_POOL : known.name();
                pools.computeIfAbsent(key, k -> new Group(jvm ? "JVM-Internal" : known.type(), known.category()))
                    .threads.add(thread);
                continue;
            }
            String prefix = ThreadPoolDetector.extractPrefix(name);
            if (prefix == null) {
                standalone.add(thread);
            } else {
                byPrefix.computeIfAbsent(prefix, k -> new ArrayList<>()).add(thread);
            }
        }
        byPrefix.forEach((prefix, members) -> {
            if (members.size() >= 2 || pools.containsKey(prefix)) {
                pools.computeIfAbsent(prefix, k -> new Group("Custom", ThreadPoolDetector.CATEGORY_CUSTOM))
                    .threads.addAll(members);
            } else {
                standalone.addAll(members);
            }
        });
        if (!standalone.isEmpty()) {
            Group g = new Group("Standalone", "STANDALONE");
            g.threads.addAll(standalone);
            pools.put(STANDALONE_POOL, g);
        }
        return pools;
    }

    private static JfrDocuments.ThreadPoolGroup summarize(
            String name, String type, String category, List<JfrDocuments.PlatformThread> threads, Window window) {
        int n = window.buckets;
        int[] created = new int[n];
        int[] ended = new int[n];
        int[] aliveDiff = new int[n + 1];
        int createdTotal = 0;
        int endedTotal = 0;
        int aliveAtEnd = 0;
        long allocatedTotal = 0;
        List<JfrDocuments.ThreadRow> rows = new ArrayList<>(threads.size());
        for (JfrDocuments.PlatformThread thread : threads) {
            boolean wasCreated = created(thread);
            boolean wasEnded = ended(thread);
            int first = 0;
            int last = n - 1;
            if (wasCreated) {
                first = window.index(thread.startEpochMs());
                created[first]++;
                createdTotal++;
            }
            if (wasEnded) {
                int index = window.index(thread.endEpochMs());
                ended[index]++;
                endedTotal++;
                last = index - 1;
            } else {
                aliveAtEnd++;
            }
            if (first <= last) {
                aliveDiff[first]++;
                aliveDiff[last + 1]--;
            }
            Long allocated = null;
            Long recorded = null;
            if (!thread.allocation().isEmpty()) {
                allocated = thread.allocation().getLast().allocated();
                // allocated 是线程启动以来的累计值；录制前就存在的线程取首尾差，录制中新建的线程从 0 开始算。
                long base = wasCreated ? 0 : thread.allocation().getFirst().allocated();
                recorded = Math.max(0, allocated - base);
                allocatedTotal += recorded;
            }
            rows.add(new JfrDocuments.ThreadRow(
                thread.javaThreadId(), thread.name(), thread.parentName(),
                thread.startEpochMs(), thread.endEpochMs(),
                !wasCreated, !wasEnded, allocated, recorded));
        }
        int[] alive = new int[n];
        int running = 0;
        int peakAlive = 0;
        for (int i = 0; i < n; i++) {
            running += aliveDiff[i];
            alive[i] = running;
            peakAlive = Math.max(peakAlive, running);
        }
        rows.sort(Comparator.comparing((JfrDocuments.ThreadRow row) -> row.name() == null ? "" : row.name()));
        return new JfrDocuments.ThreadPoolGroup(
            name, type, category, threads.size(), createdTotal, endedTotal, aliveAtEnd, peakAlive, allocatedTotal,
            new JfrDocuments.LifecycleSeries(created, ended, alive), rows);
    }

    private static boolean created(JfrDocuments.PlatformThread thread) {
        return !thread.existedBefore() && thread.startEpochMs() != null;
    }

    private static boolean ended(JfrDocuments.PlatformThread thread) {
        return !thread.aliveAtEnd() && thread.endEpochMs() != null;
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    private static final class Group {
        final String type;
        final String category;
        final List<JfrDocuments.PlatformThread> threads = new ArrayList<>();

        Group(String type, String category) {
            this.type = type;
            this.category = category;
        }
    }

    private static final class Window {
        final long start;
        final long bucketMillis;
        final int buckets;
        final long[] lastStat;

        Window(long start, long bucketMillis, int buckets) {
            this.start = start;
            this.bucketMillis = bucketMillis;
            this.buckets = buckets;
            this.lastStat = new long[buckets];
        }

        int index(long epochMs) {
            long i = (epochMs - start) / bucketMillis;
            return (int) Math.max(0, Math.min(buckets - 1, i));
        }
    }
}
