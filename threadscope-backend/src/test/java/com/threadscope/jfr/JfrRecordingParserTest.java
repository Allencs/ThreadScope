package com.threadscope.jfr;

import com.threadscope.config.JfrProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JfrRecordingParserTest {

    private static JfrDocuments.ParseResult parsed;

    @BeforeAll
    static void parseSample() {
        Path sample = Path.of("..", "samples", "jfr", "hotmethods_before.jfr");
        var properties = new JfrProperties(512, "", 14, 5000, 120, 50, 1);
        parsed = new JfrRecordingParser(properties).parse(sample);
    }

    @Test
    void integerEqualsIsSeventyThreePercentSelfAndItsCallerIsIndexOf() {
        var equals = parsed.hotspot().methods().get("java.lang.Integer#equals(Object)");
        assertEquals(24526, parsed.hotspot().totalSamples());
        assertEquals(17906, equals.self());
        assertEquals(17906, equals.inclusive());
        assertEquals(17906L, equals.callers().get("java.util.LinkedList#indexOf(Object)"));
        assertEquals(73.01, percent(equals.self()), 0.01);
    }

    @Test
    void searchingIndexOfShowsThatMethodAndItsCallers() {
        var list = JfrHotspotQuery.list(parsed.hotspot(), JfrStackIndex.of(parsed.hotspot()), "indexOf", "self", 50);
        assertTrue(list.exactPaths());
        assertTrue(list.methods().stream().allMatch(row -> row.label().toLowerCase().contains("indexof")));
        var indexOf = list.methods().stream()
            .filter(row -> row.label().equals("LinkedList.indexOf(Object)"))
            .findFirst()
            .orElseThrow();
        assertTrue(indexOf.expand());
        assertTrue(contains(indexOf.callers(), "LinkedList.contains(Object)"));
        assertTrue(list.methods().size() < 10);
    }

    @Test
    void dedupedStacksAddUpToAllSamples() {
        var hotspot = parsed.hotspot();
        assertEquals(hotspot.totalSamples(), hotspot.stacks().stream().mapToLong(JfrDocuments.StackSample::samples).sum());
        assertTrue(hotspot.stacks().size() < hotspot.totalSamples());
        var row = JfrHotspotQuery.list(hotspot, JfrStackIndex.of(hotspot), "", "self", 1).methods().getFirst();
        assertEquals("Integer.equals(Object)", row.label());
        assertEquals("self", row.basis());
        assertEquals("LinkedList.indexOf(Object)", row.callers().getFirst().label());
        assertEquals(100.0, row.callers().getFirst().parentPercent(), 0.01);
        assertEquals(row.selfSamples(), row.timeline().stream().mapToLong(Long::longValue).sum());
    }

    /** 按调用栈的时间分布统计：覆盖整份录制时与方法统计完全一致，前后两半相加等于整体。 */
    @Test
    void timeRangesPartitionTheSamples() {
        var hotspot = parsed.hotspot();
        var index = JfrStackIndex.of(hotspot);
        assertTrue(index.timed);
        int n = hotspot.bucketCount();
        int half = n / 2;
        var whole = JfrHotspotQuery.list(hotspot, index, "", "inclusive", 10_000, new JfrDocuments.BucketRange(0, n));
        var first = JfrHotspotQuery.list(hotspot, index, "", "inclusive", 10_000, new JfrDocuments.BucketRange(0, half));
        var second = JfrHotspotQuery.list(hotspot, index, "", "inclusive", 10_000, new JfrDocuments.BucketRange(half, n));
        assertEquals(hotspot.totalSamples(), whole.totalSamples());
        assertEquals(hotspot.totalSamples(), first.totalSamples() + second.totalSamples());
        assertEquals(hotspot.bucketSamples().subList(0, half).stream().mapToLong(Long::longValue).sum(), first.totalSamples());

        java.util.Map<String, long[]> halves = new java.util.HashMap<>();
        for (var list : java.util.List.of(first, second)) {
            for (var row : list.methods()) {
                long[] sum = halves.computeIfAbsent(row.methodKey(), k -> new long[2]);
                sum[0] += row.selfSamples();
                sum[1] += row.inclusiveSamples();
            }
        }
        assertEquals(hotspot.methods().size(), whole.methods().size());
        for (var row : whole.methods()) {
            var stat = hotspot.methods().get(row.methodKey());
            assertEquals(stat.self(), row.selfSamples(), row.methodKey());
            assertEquals(stat.inclusive(), row.inclusiveSamples(), row.methodKey());
            assertEquals(stat.self(), halves.get(row.methodKey())[0], row.methodKey());
            assertEquals(stat.inclusive(), halves.get(row.methodKey())[1], row.methodKey());
        }
        assertEquals(half, first.rangeTo());
        assertTrue(first.rangeSupported());
    }

    @Test
    void cpuLoadIsAlignedToSampleBuckets() {
        var cpu = JfrCpuTimeline.build(parsed.hotspot());
        assertTrue(cpu != null && cpu.hwThreads() != null && cpu.hwThreads() > 0);
        assertEquals(parsed.hotspot().bucketCount(), cpu.jvmUser().size());
        assertTrue(cpu.jvmUser().stream().anyMatch(v -> v != null && v > 0));
        assertTrue(cpu.container() == null && cpu.throttled() == null && !"container".equals(cpu.deployment()));
        assertEquals(cpu.hwThreads().doubleValue(), cpu.limitCores());
    }

    @Test
    void descriptorsBecomeReadableParameterLists() {
        assertEquals("(Object, Object[])", JfrRecordingParser.params("(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"));
        assertEquals("(byte[], long, int, CharSequence, int, int)", JfrRecordingParser.params("([BJILjava/lang/CharSequence;II)I"));
        assertEquals("(int[][], CompletableFuture$UniWhenComplete)",
            JfrRecordingParser.params("([[ILjava/util/concurrent/CompletableFuture$UniWhenComplete;)V"));
        assertEquals("()", JfrRecordingParser.params("()V"));
    }

    @Test
    void sampleHasOneThreadDumpAndNoThreadStartStacks() {
        assertEquals(1, parsed.threadDumps().size());
        assertTrue(parsed.threadDumps().getFirst().contains("Full thread dump"));
        assertTrue(parsed.threads().platform().stream().allMatch(thread -> thread.startStack().isEmpty()));
        assertTrue(parsed.threads().virtualThreads().started() == 0);
    }

    private static boolean contains(java.util.List<JfrDocuments.HotspotCaller> callers, String label) {
        for (var caller : callers) {
            if (label.equals(caller.label()) || contains(caller.callers(), label)) return true;
        }
        return false;
    }

    private static double percent(long samples) {
        return Math.round(samples * 10000.0 / parsed.hotspot().totalSamples()) / 100.0;
    }
}
