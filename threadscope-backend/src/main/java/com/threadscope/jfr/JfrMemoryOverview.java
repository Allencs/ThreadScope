package com.threadscope.jfr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 内存标签页的概览：把 GC、堆、分配停顿等事件对齐到热点时间桶，并给出诊断结论。
 * 时间窗口外的事件（例如录制开始前那次 GC）不进时间桶。
 */
final class JfrMemoryOverview {

    private static final int POOL_LIMIT = 12;
    private static final int POOL_THREAD_LIMIT = 5;
    private static final int STALL_THREAD_LIMIT = 10;

    private JfrMemoryOverview() {}

    static JfrDocuments.MemoryOverview build(JfrDocuments.MemoryDocument doc, JfrDocuments.ThreadDocument threads) {
        int n = Math.max(1, doc.bucketCount());
        Buckets b = new Buckets(doc.startEpochMs(), doc.endEpochMs(), n);
        JfrDocuments.GcDocument gc = doc.gc();

        List<JfrDocuments.AllocationSource> sources = doc.profiles().stream()
            .map(p -> new JfrDocuments.AllocationSource(p.source(), p.events(), p.stacks().totalSamples(),
                p.coverStartEpochMs(), p.coverEndEpochMs(), p.stacks().bucketSamples()))
            .toList();

        Long measuredTotal = doc.measuredBytes() == null ? null
            : doc.measuredBytes().stream().mapToLong(Long::longValue).sum();

        Map<String, long[]> byCollector = new LinkedHashMap<>();
        Long[] gcCount = new Long[n];
        Long[] gcPause = new Long[n];
        for (JfrDocuments.GcCollection c : gc.collections()) {
            if (!b.inside(c.epochMs())) continue;
            int i = b.index(c.epochMs());
            gcCount[i] = (gcCount[i] == null ? 0 : gcCount[i]) + 1;
            gcPause[i] = (gcPause[i] == null ? 0 : gcPause[i]) + c.pauseNanos();
            long[] sum = byCollector.computeIfAbsent(c.name() == null ? "GC" : c.name(), k -> new long[4]);
            sum[0]++;
            sum[1] += c.pauseNanos();
            sum[2] = Math.max(sum[2], c.longestPauseNanos());
            sum[3] += c.durationNanos();
        }
        List<JfrDocuments.GcSummary> collectors = byCollector.entrySet().stream()
            .map(e -> new JfrDocuments.GcSummary(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]))
            .sorted(Comparator.comparingLong(JfrDocuments.GcSummary::count).reversed())
            .toList();

        Long[] heapPeak = new Long[n];
        Long[] heapFloor = new Long[n];
        Long[] heapCommitted = new Long[n];
        for (JfrDocuments.HeapPoint p : gc.heap()) {
            if (!b.inside(p.epochMs())) continue;
            int i = b.index(p.epochMs());
            heapPeak[i] = max(heapPeak[i], p.used());
            if (p.committed() > 0) heapCommitted[i] = max(heapCommitted[i], p.committed());
            if (p.afterGc()) heapFloor[i] = heapFloor[i] == null ? p.used() : Math.min(heapFloor[i], p.used());
        }

        Long[] stallCount = new Long[n];
        Long[] stallNanos = new Long[n];
        long stallTotal = 0;
        long stallLongest = 0;
        Map<String, long[]> stallThreads = new HashMap<>();
        for (JfrDocuments.StallEvent s : gc.stalls()) {
            stallTotal += s.durationNanos();
            stallLongest = Math.max(stallLongest, s.durationNanos());
            long[] t = stallThreads.computeIfAbsent(s.thread() == null ? "?" : s.thread(), k -> new long[2]);
            t[0]++;
            t[1] += s.durationNanos();
            if (!b.inside(s.epochMs())) continue;
            int i = b.index(s.epochMs());
            stallCount[i] = (stallCount[i] == null ? 0 : stallCount[i]) + 1;
            stallNanos[i] = (stallNanos[i] == null ? 0 : stallNanos[i]) + s.durationNanos();
        }
        List<JfrDocuments.StallThread> topStalled = stallThreads.entrySet().stream()
            .sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]))
            .limit(STALL_THREAD_LIMIT)
            .map(e -> new JfrDocuments.StallThread(e.getKey(), e.getValue()[0], e.getValue()[1]))
            .toList();

        List<JfrDocuments.PoolAllocation> pools = pools(threads, doc.startEpochMs(), doc.endEpochMs());
        Long[] metaspace = series(gc.metaspace(), b);
        Long[] rss = series(gc.rss(), b);
        Long[] container = series(gc.container(), b);

        var overview = new JfrDocuments.MemoryOverview(
            doc.startEpochMs(), doc.endEpochMs(), n, sources,
            doc.measuredBytes(), doc.measuredIntervalMs(), measuredTotal,
            gc.youngCollector(), gc.oldCollector(), gc.heapMax(), collectors,
            Arrays.asList(gcCount), Arrays.asList(gcPause),
            Arrays.asList(heapPeak), Arrays.asList(heapFloor), Arrays.asList(heapCommitted),
            gc.stallCount(), stallTotal, stallLongest,
            Arrays.asList(stallCount), Arrays.asList(stallNanos), topStalled,
            Arrays.asList(metaspace), Arrays.asList(rss), Arrays.asList(container), gc.containerLimit(),
            pools, List.of());
        return withFindings(overview, doc);
    }

    private static JfrDocuments.MemoryOverview withFindings(JfrDocuments.MemoryOverview o, JfrDocuments.MemoryDocument doc) {
        List<JfrDocuments.MemoryFinding> findings = new ArrayList<>();
        double windowSeconds = Math.max(1, o.endEpochMs() - o.startEpochMs()) / 1000.0;

        if (o.stallCount() > 0) {
            long threads = doc.gc().stalls().stream().map(JfrDocuments.StallEvent::thread).distinct().count();
            findings.add(new JfrDocuments.MemoryFinding("critical",
                "ZGC 分配停顿 " + o.stallCount() + " 次，累计 " + millis(o.stallNanos()) + "，最长 " + millis(o.longestStallNanos()),
                "分配速度超过了 GC 回收速度，" + threads + " 个线程在分配对象时被迫等待 GC 腾出空间，表现为请求 RT 毛刺。"
                    + "降低下方分配热点的分配量，或调大堆 / SoftMaxHeapSize、增加并发 GC 线程。"));
        }

        Long heapMax = o.heapMax();
        List<Long> floors = o.heapFloor().stream().filter(v -> v != null).toList();
        if (heapMax != null && floors.size() >= 4) {
            int q = Math.max(1, floors.size() / 4);
            double head = floors.subList(0, q).stream().mapToLong(Long::longValue).average().orElse(0);
            double tail = floors.subList(floors.size() - q, floors.size()).stream().mapToLong(Long::longValue).average().orElse(0);
            long top = floors.stream().mapToLong(Long::longValue).max().orElse(0);
            if (top > heapMax * 0.85) {
                findings.add(new JfrDocuments.MemoryFinding("critical",
                    "GC 后堆占用最高达到 " + percent(top, heapMax) + "（" + bytes(top) + " / " + bytes(heapMax) + "）",
                    "GC 后剩下的基本都是存活对象，余量太小会导致 GC 越来越频繁，甚至 OOM。需要结合堆 Dump 看是谁持有这些对象。"));
            } else if (tail - head > heapMax * 0.15 && tail > heapMax * 0.5) {
                findings.add(new JfrDocuments.MemoryFinding("warn",
                    "GC 后堆占用持续上升：" + bytes((long) head) + " → " + bytes((long) tail),
                    "录制期间存活对象在增长，可能是缓存膨胀、队列积压或内存泄漏。录制时间越长，这个判断越可靠。"));
            }
        }

        long pause = o.collectors().stream().mapToLong(JfrDocuments.GcSummary::pauseNanos).sum();
        long longest = o.collectors().stream().mapToLong(JfrDocuments.GcSummary::longestPauseNanos).max().orElse(0);
        if (pause / 1e9 > windowSeconds * 0.05) {
            findings.add(new JfrDocuments.MemoryFinding("warn",
                "GC 停顿占录制时长的 " + String.format(Locale.ROOT, "%.1f%%", pause / 1e9 * 100 / windowSeconds),
                "停顿期间所有 Java 线程都暂停。先看分配热点降低分配速率，再考虑调整堆大小或收集器。"));
        }
        if (longest >= 200_000_000L) {
            findings.add(new JfrDocuments.MemoryFinding("warn", "最长一次 GC 停顿 " + millis(longest),
                "单次停顿超过 200ms，会直接体现在接口 RT 上。"));
        }

        if (o.containerLimit() != null) {
            long peak = o.container().stream().filter(v -> v != null).mapToLong(Long::longValue).max().orElse(0);
            if (peak > o.containerLimit() * 0.9) {
                findings.add(new JfrDocuments.MemoryFinding("warn",
                    "容器内存使用达到上限的 " + percent(peak, o.containerLimit()),
                    "接近 cgroup 内存上限时会被 OOM Killer 杀掉。堆外内存（直接内存、线程栈、元空间、JIT 代码缓存）也计入容器内存。"));
            }
        }

        JfrDocuments.AllocationSource tlab = source(o, JfrMemoryCollector.TLAB);
        JfrDocuments.AllocationSource sample = source(o, JfrMemoryCollector.SAMPLE);
        if (tlab == null && sample == null) {
            findings.add(new JfrDocuments.MemoryFinding("info", "录制里没有分配调用栈",
                "没有 ObjectAllocationSample / ObjectAllocationInNewTLAB 事件，只能看到线程级的分配量和 GC 情况。"
                    + "JDK 16+ 用 default 或 profile 模板录制即可；JDK 11 需要用 profile 模板（开启 TLAB 分配事件）。"));
        } else if (tlab == null) {
            findings.add(new JfrDocuments.MemoryFinding("info", "按 ObjectAllocationSample 估算分配（default 模板）",
                "default 模板没有 TLAB 分配事件，这里用 JDK 16+ 的分配采样：每个线程限流采样，每条样本带有距上次采样的分配字节数，"
                    + "整体比例可信，但单个低频方法的估算误差较大。需要更细的结果可用 profile 模板录制。"));
        }
        if (tlab != null && tlab.coverStartEpochMs() != null) {
            long covered = tlab.coverEndEpochMs() - tlab.coverStartEpochMs();
            if (covered < (o.endEpochMs() - o.startEpochMs()) * 0.8) {
                findings.add(new JfrDocuments.MemoryFinding("info",
                    "TLAB 分配事件只覆盖了 " + seconds(covered) + "（录制共 " + seconds(o.endEpochMs() - o.startEpochMs()) + "）",
                    "TLAB 事件开销大，通常只在一段时间内开启。分配统计只代表这段时间"
                        + (sample != null ? "；切换到「分配采样」可以看整份录制。" : "。")));
            }
        }
        if (o.measuredTotal() != null && o.measuredTotal() > 0) {
            findings.add(new JfrDocuments.MemoryFinding("info",
                "实际分配速率约 " + bytes((long) (o.measuredTotal() / windowSeconds)) + "/s",
                "来自 ThreadAllocationStatistics，是各线程分配计数器的差值，不是估算。下方方法列表的百分比可以乘以这个速率估计方法的分配速率。"));
        }
        return new JfrDocuments.MemoryOverview(
            o.startEpochMs(), o.endEpochMs(), o.bucketCount(), o.sources(), o.measuredBytes(), o.measuredIntervalMs(),
            o.measuredTotal(), o.youngCollector(), o.oldCollector(), o.heapMax(), o.collectors(), o.gcCount(),
            o.gcPauseNanos(), o.heapPeak(), o.heapFloor(), o.heapCommitted(), o.stallCount(), o.stallNanos(),
            o.longestStallNanos(), o.stallBuckets(), o.stallNanosBuckets(), o.stallThreads(), o.metaspace(), o.rss(),
            o.container(), o.containerLimit(), o.pools(), findings);
    }

    /** 线程池的实际分配量（ThreadAllocationStatistics），与线程标签页的口径一致。 */
    private static List<JfrDocuments.PoolAllocation> pools(JfrDocuments.ThreadDocument threads, long start, long end) {
        if (threads == null) return List.of();
        return JfrThreadOverview.build(threads, start, end).pools().stream()
            .filter(p -> p.recordedAllocatedBytes() > 0)
            .sorted(Comparator.comparingLong(JfrDocuments.ThreadPoolGroup::recordedAllocatedBytes).reversed())
            .limit(POOL_LIMIT)
            .map(p -> new JfrDocuments.PoolAllocation(p.name(), p.recordedAllocatedBytes(), p.total(),
                p.threads().stream()
                    .filter(t -> t.recordedAllocatedBytes() != null && t.recordedAllocatedBytes() > 0)
                    .sorted(Comparator.comparingLong(JfrDocuments.ThreadRow::recordedAllocatedBytes).reversed())
                    .limit(POOL_THREAD_LIMIT)
                    .map(t -> new JfrDocuments.ThreadAllocation(t.name(), t.recordedAllocatedBytes()))
                    .toList()))
            .toList();
    }

    private static JfrDocuments.AllocationSource source(JfrDocuments.MemoryOverview o, String name) {
        return o.sources().stream().filter(s -> s.source().equals(name)).findFirst().orElse(null);
    }

    private static Long[] series(List<JfrDocuments.MemoryPoint> points, Buckets b) {
        Long[] values = new Long[b.n];
        for (JfrDocuments.MemoryPoint p : points) {
            if (!b.inside(p.epochMs())) continue;
            int i = b.index(p.epochMs());
            values[i] = max(values[i], p.value());
        }
        return values;
    }

    private static Long max(Long a, long b) {
        return a == null ? b : Math.max(a, b);
    }

    static String bytes(long v) {
        double gb = v / (1024.0 * 1024 * 1024);
        if (gb >= 1) return String.format(Locale.ROOT, "%.1f GB", gb);
        double mb = v / (1024.0 * 1024);
        return mb >= 1 ? String.format(Locale.ROOT, "%.0f MB", mb) : String.format(Locale.ROOT, "%.0f KB", v / 1024.0);
    }

    private static String millis(long nanos) {
        double ms = nanos / 1e6;
        return ms >= 1000 ? String.format(Locale.ROOT, "%.2f s", ms / 1000) : String.format(Locale.ROOT, "%.0f ms", ms);
    }

    private static String seconds(long ms) {
        return Math.round(ms / 1000.0) + " 秒";
    }

    private static String percent(long part, long total) {
        return String.format(Locale.ROOT, "%.0f%%", part * 100.0 / Math.max(1, total));
    }

    private record Buckets(long start, long end, int n) {
        boolean inside(long epochMs) {
            return epochMs >= start && epochMs <= end;
        }

        int index(long epochMs) {
            if (n <= 1) return 0;
            long span = Math.max(1, end - start);
            return (int) Math.max(0, Math.min(n - 1, (epochMs - start) * n / span));
        }
    }
}
