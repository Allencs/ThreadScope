package com.threadscope.jfr;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JfrHotspotQueryTest {

    private static JfrDocuments.MethodStat stat(long self, Map<String, Long> callers) {
        return new JfrDocuments.MethodStat(self, self, callers, Map.of(), Map.of());
    }

    /** 第一个调用者背后是一张很宽的调用图，也不能把同层的其他调用者挤掉。 */
    @Test
    void siblingsSurviveWhenFirstCallerHasHugeCallGraph() {
        Map<String, JfrDocuments.MethodStat> methods = new HashMap<>();
        methods.put("p.Leaf#run", stat(100, new LinkedHashMap<>(Map.of("p.A#a", 60L, "p.B#b", 30L, "p.C#c", 10L))));
        Map<String, Long> wide = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) wide.put("p.X" + i + "#x", 10L - i);
        methods.put("p.A#a", stat(0, wide));
        for (int i = 0; i < 8; i++) methods.put("p.X" + i + "#x", stat(0, wide));
        methods.put("p.B#b", stat(0, Map.of("p.Main#main", 30L)));
        methods.put("p.C#c", stat(0, Map.of()));
        methods.put("p.Main#main", stat(0, Map.of()));
        var doc = new JfrDocuments.HotspotDocument(100, 0, 1, 1, List.of(100L), methods);

        var list = JfrHotspotQuery.list(doc, null, "", "self", 10);
        var row = list.methods().getFirst();

        assertFalse(list.exactPaths());
        assertEquals(List.of("A.a", "B.b", "C.c"), row.callers().stream().map(JfrDocuments.HotspotCaller::label).toList());
        assertEquals(100.0, row.callers().stream().mapToDouble(JfrDocuments.HotspotCaller::parentPercent).sum(), 0.01);
        assertEquals("Main.main", row.callers().get(1).callers().getFirst().label());
        assertTrue(count(row.callers()) <= 240);
        assertTrue(anyTruncated(row.callers()));

        var lazy = JfrHotspotQuery.expand(doc, null, List.of("p.Leaf#run", "p.X3#x"), "self", "");
        assertEquals(8, lazy.size());
    }

    /**
     * Util 同时被 A、B（Leaf 这条链）和 C（别的热点）调用。按方法名聚合时 Leaf → Util 下会混进 C，
     * 按路径统计只剩 A、B，比例是这条链上的真实比例。
     */
    @Test
    void callerTreeFollowsExactPaths() {
        var doc = stacks(
            new Object[] {60, false, "p.Leaf#run", "p.Util#u", "p.A#a", "p.Main#main"},
            new Object[] {30, false, "p.Leaf#run", "p.Util#u", "p.B#b", "p.Main#main"},
            new Object[] {10, true, "p.Leaf#run", "p.Util#u"},
            new Object[] {100, false, "p.Other#o", "p.Util#u", "p.C#c", "p.Main#main"});
        var index = JfrStackIndex.of(doc);

        var list = JfrHotspotQuery.list(doc, index, "", "self", 10);
        assertTrue(list.exactPaths());
        var leaf = list.methods().stream().filter(r -> r.label().equals("Leaf.run")).findFirst().orElseThrow();
        assertEquals("self", leaf.basis());
        var util = leaf.callers().getFirst();
        assertEquals("Util.u", util.label());
        assertEquals(100.0, util.parentPercent(), 0.01);

        List<String> labels = util.callers().stream().map(JfrDocuments.HotspotCaller::label).toList();
        assertFalse(labels.contains("C.c"));
        assertEquals("A.a", util.callers().get(0).label());
        assertEquals(60.0, util.callers().get(0).parentPercent(), 0.01);
        assertEquals("B.b", util.callers().get(1).label());
        assertEquals(30.0, util.callers().get(1).parentPercent(), 0.01);
        var cut = util.callers().get(2);
        assertEquals("truncated", cut.kind());
        assertEquals(10.0, cut.parentPercent(), 0.01);

        var main = util.callers().get(0).callers().getFirst();
        assertEquals("Main.main", main.label());
        assertEquals(60, main.samples());
        assertTrue(main.callers().isEmpty());

        var lazy = JfrHotspotQuery.expand(doc, index, List.of("p.Leaf#run", "p.Util#u", "p.B#b"), "self", "");
        assertEquals(List.of("Main.main"), lazy.stream().map(JfrDocuments.HotspotCaller::label).toList());
        assertEquals(30, lazy.getFirst().samples());
    }

    @Test
    void inclusiveBasisCountsEveryStackContainingTheRoot() {
        var doc = stacks(
            new Object[] {70, false, "p.Leaf#run", "p.Util#u", "p.A#a"},
            new Object[] {30, false, "p.Util#u", "p.B#b"});
        var index = JfrStackIndex.of(doc);

        var util = JfrHotspotQuery.list(doc, index, "Util", "self", 10).methods().getFirst();
        assertEquals("Util.u", util.label());
        assertEquals("self", util.basis());
        assertEquals(List.of("B.b"), util.callers().stream().map(JfrDocuments.HotspotCaller::label).toList());

        var inclusive = JfrHotspotQuery.list(doc, index, "Util", "inclusive", 10).methods().getFirst();
        assertEquals("inclusive", inclusive.basis());
        assertEquals(70.0, inclusive.callers().getFirst().parentPercent(), 0.01);
        assertEquals("A.a", inclusive.callers().getFirst().label());
    }

    /** 过路方法 self 为 0，按 inclusive 排序时也必须出现。 */
    @Test
    void inclusiveRankingKeepsPassThroughMethods() {
        var doc = stacks(
            new Object[] {70, false, "p.Leaf#run", "p.Filter#doFilter", "p.Main#main"},
            new Object[] {30, false, "p.Other#run", "p.Filter#doFilter", "p.Main#main"});
        var index = JfrStackIndex.of(doc);

        var inclusive = JfrHotspotQuery.list(doc, index, "", "inclusive", 10).methods();
        assertEquals(4, inclusive.size());
        assertEquals(java.util.Set.of("Filter.doFilter", "Main.main"),
            java.util.Set.of(inclusive.get(0).label(), inclusive.get(1).label()));
        assertEquals(100.0, inclusive.getFirst().inclusivePercent(), 0.01);
        assertEquals(0, inclusive.getFirst().selfSamples());

        var self = JfrHotspotQuery.list(doc, index, "", "self", 10).methods();
        assertEquals(List.of("Leaf.run", "Other.run"), self.stream().map(JfrDocuments.HotspotRow::label).toList());
    }

    /** 方法 CPU = 桶内 JVM CPU × 方法在桶内的样本占比，再按有 CPU 数据的桶取平均。 */
    @Test
    void machineCpuWeightsJvmCpuBySampleShare() {
        var timeline = new JfrDocuments.CpuTimeline(0, 2, 2, 32, 16,
            List.of(100L, 50L), List.of(0.20f, 0.10f), List.of(0.05f, 0.0f), List.of(0.5f, 0.3f),
            null, null, null, 32.0, null);
        double cpu = JfrCpuTimeline.machineCpu(timeline, new long[] {10, 25});
        assertEquals((0.25 * 0.1 + 0.10 * 0.5) / 2, cpu, 1e-6);
    }

    @Test
    void containerLimitPrefersQuotaOverHostCores() {
        var quota = new JfrDocuments.ContainerInfo("cgroupv2", 4.0, 4L, 2048L, null, null);
        var container = new JfrDocuments.CpuDocument(32, 16, List.of(), "KVM virtualization", quota, null);
        assertEquals("container", JfrCpuTimeline.deployment(container));
        assertEquals(4.0, JfrCpuTimeline.limitCores(container));

        var sharesOnly = new JfrDocuments.ContainerInfo("cgroupv1", null, 6L, 1024L, null, null);
        assertEquals(6.0, JfrCpuTimeline.limitCores(
            new JfrDocuments.CpuDocument(32, 16, List.of(), null, sharesOnly, null)));

        var vm = new JfrDocuments.CpuDocument(8, 4, List.of(), "KVM virtualization", null, null);
        assertEquals("vm", JfrCpuTimeline.deployment(vm));
        assertEquals(8.0, JfrCpuTimeline.limitCores(vm));
        assertEquals("physical", JfrCpuTimeline.deployment(
            new JfrDocuments.CpuDocument(8, 4, List.of(), "No virtualization detected", null, null)));
        assertNull(JfrCpuTimeline.deployment(new JfrDocuments.CpuDocument(8, 4, List.of())));
    }

    /** 限流计数是累计值：每个桶取中点所在窗口的增量比例，窗口外为 null。 */
    @Test
    void throttlingIsTheDeltaOfTheWindowContainingEachBucket() {
        var points = List.of(
            new JfrDocuments.ThrottlePoint(30, 1000, 900, 0),
            new JfrDocuments.ThrottlePoint(60, 1300, 1020, 0),
            new JfrDocuments.ThrottlePoint(90, 1600, 1020, 0));
        List<Float> values = JfrCpuTimeline.throttled(points, 0, 100, 10);
        assertNull(values.get(0));
        assertNull(values.get(2));
        assertEquals(0.4f, values.get(3), 1e-6);
        assertEquals(0.4f, values.get(5), 1e-6);
        assertEquals(0f, values.get(6), 1e-6);
        assertEquals(0f, values.get(8), 1e-6);
        assertNull(values.get(9));
        assertNull(JfrCpuTimeline.throttled(points.subList(0, 1), 0, 100, 10));
    }

    /** 选中时间区间后，根列表和调用者树都只统计区间内的栈。 */
    @Test
    void rangeRestrictsRowsAndCallerTreeToSelectedBuckets() {
        List<String> frames = List.of("p.Leaf#run", "p.A#a", "p.B#b", "p.Main#main");
        var stacks = List.of(
            new JfrDocuments.StackSample(new int[] {0, 1, 3}, 12, false, new int[] {0, 1}, new long[] {10, 2}),
            new JfrDocuments.StackSample(new int[] {0, 2, 3}, 6, false, new int[] {1}, new long[] {6}),
            new JfrDocuments.StackSample(new int[] {1, 3}, 4, false, new int[] {0}, new long[] {4}));
        Map<String, JfrDocuments.MethodStat> methods = Map.of(
            "p.Leaf#run", new JfrDocuments.MethodStat(18, 18, Map.of(), Map.of("0", 10L, "1", 8L), Map.of("0", 10L, "1", 8L)),
            "p.A#a", new JfrDocuments.MethodStat(4, 16, Map.of(), Map.of("0", 4L), Map.of("0", 14L, "1", 2L)),
            "p.B#b", new JfrDocuments.MethodStat(0, 6, Map.of(), Map.of(), Map.of("1", 6L)),
            "p.Main#main", new JfrDocuments.MethodStat(0, 22, Map.of(), Map.of(), Map.of("0", 14L, "1", 8L)));
        var doc = new JfrDocuments.HotspotDocument(22, 0, 2, 2, List.of(14L, 8L), methods, frames, stacks, null);
        var index = JfrStackIndex.of(doc);

        var list = JfrHotspotQuery.list(doc, index, "", "self", 10, JfrDocuments.BucketRange.of(1, 2, 2));
        assertEquals(8, list.totalSamples());
        assertEquals(1, list.rangeFrom());
        assertEquals(List.of("Leaf.run"), list.methods().stream().map(JfrDocuments.HotspotRow::label).toList());
        var leaf = list.methods().getFirst();
        assertEquals(8, leaf.selfSamples());
        assertEquals(100.0, leaf.selfPercent(), 0.01);
        assertEquals(List.of("B.b", "A.a"), leaf.callers().stream().map(JfrDocuments.HotspotCaller::label).toList());
        assertEquals(75.0, leaf.callers().getFirst().parentPercent(), 0.01);
        assertEquals(List.of(10L, 8L), leaf.timeline());

        var expanded = JfrHotspotQuery.expand(doc, index, List.of("p.Leaf#run", "p.A#a"), "self", "",
            JfrDocuments.BucketRange.of(1, 2, 2));
        assertEquals(2, expanded.getFirst().samples());

        var whole = JfrHotspotQuery.list(doc, index, "", "self", 10, JfrDocuments.BucketRange.of(0, 2, 2));
        assertEquals(22, whole.totalSamples());
        assertNull(whole.rangeFrom());
    }

    @Test
    void searchFollowsTheBranchThatLeadsToTheMatch() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rows.add(new Object[] {100 - i, false, "p.Leaf#run", "p.Mid" + i + "#m", "p.Main#main"});
        }
        rows.add(new Object[] {1, false, "p.Leaf#run", "p.Mid9#m", "p.Target#run", "p.Main#main"});
        var doc = stacks(rows.toArray(Object[][]::new));

        var row = JfrHotspotQuery.list(doc, JfrStackIndex.of(doc), "run", "self", 10).methods().getFirst();
        assertEquals("Leaf.run", row.label());
        var mid9 = row.callers().stream().filter(c -> c.label().equals("Mid9.m")).findFirst().orElseThrow();
        assertEquals("Target.run", mid9.callers().getFirst().label());
        assertTrue(mid9.callers().getFirst().matched());
        var mid4 = row.callers().stream().filter(c -> c.label().equals("Mid4.m")).findFirst().orElseThrow();
        assertTrue(mid4.truncated());
    }

    private static JfrDocuments.HotspotDocument stacks(Object[]... rows) {
        List<String> frames = new ArrayList<>();
        Map<String, Integer> ids = new HashMap<>();
        Map<String, long[]> counts = new LinkedHashMap<>();
        List<JfrDocuments.StackSample> stacks = new ArrayList<>();
        long total = 0;
        for (Object[] row : rows) {
            long samples = ((Number) row[0]).longValue();
            boolean truncated = (Boolean) row[1];
            int[] ids2 = new int[row.length - 2];
            var seen = new java.util.HashSet<String>();
            for (int i = 2; i < row.length; i++) {
                String key = (String) row[i];
                ids2[i - 2] = ids.computeIfAbsent(key, k -> {
                    frames.add(k);
                    return frames.size() - 1;
                });
                long[] c = counts.computeIfAbsent(key, k -> new long[2]);
                if (i == 2) c[0] += samples;
                if (seen.add(key)) c[1] += samples;
            }
            stacks.add(new JfrDocuments.StackSample(ids2, samples, truncated));
            total += samples;
        }
        Map<String, JfrDocuments.MethodStat> methods = new HashMap<>();
        counts.forEach((key, c) -> methods.put(key, new JfrDocuments.MethodStat(c[0], c[1], Map.of(), Map.of(), Map.of())));
        return new JfrDocuments.HotspotDocument(total, 0, 1, 1, List.of(total), methods, frames, stacks, null);
    }

    private static boolean anyTruncated(List<JfrDocuments.HotspotCaller> nodes) {
        for (var node : nodes) {
            if (node.truncated() || anyTruncated(node.callers())) return true;
        }
        return false;
    }

    private static int count(List<JfrDocuments.HotspotCaller> nodes) {
        int n = 0;
        for (var node : nodes) n += 1 + count(node.callers());
        return n;
    }
}
