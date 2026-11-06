package com.threadscope.jfr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把 jdk.CPULoad 对齐到热点时间桶，并按"桶内 JVM CPU × 方法在桶内的样本占比"估算方法的 CPU 贡献。
 * jdk.CPULoad 的 JVM CPU 含 GC、JIT 等 VM 内部线程，而 ExecutionSample 只采 Java 代码，所以估算偏上限。
 */
final class JfrCpuTimeline {

    private JfrCpuTimeline() {}

    static JfrDocuments.CpuTimeline build(JfrDocuments.HotspotDocument doc) {
        if (doc == null || doc.cpu() == null || doc.cpu().points() == null || doc.cpu().points().isEmpty()) return null;
        int n = Math.max(1, doc.bucketCount());
        long start = doc.recordingStartEpochMs();
        long end = doc.recordingEndEpochMs();
        long span = Math.max(1, end - start);
        double[] user = new double[n];
        double[] system = new double[n];
        double[] machine = new double[n];
        int[] count = new int[n];
        for (JfrDocuments.CpuLoadPoint point : doc.cpu().points()) {
            if (point.epochMs() < start || point.epochMs() > end) continue;
            int b = n <= 1 ? 0 : (int) Math.min(n - 1, (point.epochMs() - start) * n / span);
            user[b] += point.jvmUser();
            system[b] += point.jvmSystem();
            machine[b] += point.machineTotal();
            count[b]++;
        }
        List<Float> jvmUser = new ArrayList<>(n);
        List<Float> jvmSystem = new ArrayList<>(n);
        List<Float> machineTotal = new ArrayList<>(n);
        boolean any = false;
        for (int b = 0; b < n; b++) {
            if (count[b] == 0) {
                jvmUser.add(null);
                jvmSystem.add(null);
                machineTotal.add(null);
                continue;
            }
            any = true;
            jvmUser.add((float) (user[b] / count[b]));
            jvmSystem.add((float) (system[b] / count[b]));
            machineTotal.add((float) (machine[b] / count[b]));
        }
        if (!any) return null;
        List<Long> samples = doc.bucketSamples() == null ? List.of() : doc.bucketSamples();
        JfrDocuments.CpuDocument cpu = doc.cpu();
        return new JfrDocuments.CpuTimeline(start, end, n, cpu.hwThreads(), cpu.cores(),
            samples, jvmUser, jvmSystem, machineTotal,
            deployment(cpu), cpu.virtualization(), cpu.container(), limitCores(cpu),
            throttled(cpu.throttling(), start, span, n));
    }

    static String deployment(JfrDocuments.CpuDocument cpu) {
        if (cpu.container() != null) return "container";
        if (cpu.virtualization() == null) return null;
        return cpu.virtualization().toLowerCase(Locale.ROOT).contains("no virtualization") ? "physical" : "vm";
    }

    /** 容器以 quota 为准（CFS 按 quota 限流），没设 quota 时退回 effectiveCpuCount（cpuset / shares 推算）。 */
    static Double limitCores(JfrDocuments.CpuDocument cpu) {
        Integer hw = cpu.hwThreads();
        JfrDocuments.ContainerInfo container = cpu.container();
        if (container != null) {
            if (container.quotaCores() != null) {
                return hw == null ? container.quotaCores() : Math.min(container.quotaCores(), hw);
            }
            if (container.effectiveCpuCount() != null) return container.effectiveCpuCount().doubleValue();
        }
        return hw == null ? null : hw.doubleValue();
    }

    /**
     * 限流计数是累计值、约 30 秒一条，比热点时间桶粗得多：每个桶取它中点所在窗口的
     * Δ throttledSlices / Δ elapsedSlices。不在任何窗口内的桶为 null。
     */
    static List<Float> throttled(List<JfrDocuments.ThrottlePoint> points, long start, long span, int n) {
        if (points == null || points.size() < 2) return null;
        List<Float> values = new ArrayList<>(n);
        boolean any = false;
        int k = 0;
        for (int b = 0; b < n; b++) {
            double mid = start + (b + 0.5) * span / n;
            while (k + 1 < points.size() && points.get(k + 1).epochMs() < mid) k++;
            if (k + 1 >= points.size() || points.get(k).epochMs() > mid) {
                values.add(null);
                continue;
            }
            JfrDocuments.ThrottlePoint from = points.get(k);
            JfrDocuments.ThrottlePoint to = points.get(k + 1);
            long elapsed = to.elapsedSlices() - from.elapsedSlices();
            long throttledSlices = to.throttledSlices() - from.throttledSlices();
            if (elapsed <= 0 || throttledSlices < 0) {
                values.add(null);
                continue;
            }
            values.add((float) Math.min(1.0, (double) throttledSlices / elapsed));
            any = true;
        }
        return any ? values : null;
    }

    static long[] dense(Map<String, Long> sparse, int n) {
        long[] values = new long[Math.max(1, n)];
        if (sparse == null) return values;
        sparse.forEach((index, count) -> {
            int i = Integer.parseInt(index);
            if (i >= 0 && i < values.length) values[i] = count;
        });
        return values;
    }

    /** 录制期间平均占整机 CPU 的比例；没有 CPU 数据时返回 null。 */
    static Double machineCpu(JfrDocuments.CpuTimeline timeline, long[] methodBuckets) {
        return machineCpu(timeline, methodBuckets, null);
    }

    /** range 不为 null 时只在区间内的桶上取平均。 */
    static Double machineCpu(JfrDocuments.CpuTimeline timeline, long[] methodBuckets, JfrDocuments.BucketRange range) {
        if (timeline == null) return null;
        double sum = 0;
        int counted = 0;
        int from = range == null ? 0 : range.from();
        int to = range == null ? timeline.bucketCount() : Math.min(range.to(), timeline.bucketCount());
        for (int b = from; b < to; b++) {
            Float user = timeline.jvmUser().get(b);
            if (user == null) continue;
            counted++;
            long total = b < timeline.samples().size() ? timeline.samples().get(b) : 0;
            if (total <= 0 || b >= methodBuckets.length) continue;
            sum += (user + timeline.jvmSystem().get(b)) * methodBuckets[b] / total;
        }
        return counted == 0 ? null : sum / counted;
    }
}
