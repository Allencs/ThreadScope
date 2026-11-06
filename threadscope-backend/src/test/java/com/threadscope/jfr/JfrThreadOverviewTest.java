package com.threadscope.jfr;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JfrThreadOverviewTest {

    private static final long T0 = 1_000_000L;

    private static JfrDocuments.PlatformThread thread(long id, String name, Long start, Long end) {
        return thread(id, name, start, end, List.of());
    }

    private static JfrDocuments.PlatformThread thread(
            long id, String name, Long start, Long end, List<JfrDocuments.AllocPoint> allocation) {
        return new JfrDocuments.PlatformThread(id, name, null, null, null, start, end,
            start == null, end == null, List.of(), List.of(), allocation);
    }

    @Test
    void recordedAllocationExcludesBytesAllocatedBeforeRecording() {
        var old = thread(1, "worker-1", null, null, List.of(
            new JfrDocuments.AllocPoint(T0, 10_000), new JfrDocuments.AllocPoint(T0 + 30_000, 12_500)));
        var fresh = thread(2, "worker-2", T0 + 1_000, null, List.of(
            new JfrDocuments.AllocPoint(T0 + 2_000, 300), new JfrDocuments.AllocPoint(T0 + 30_000, 800)));
        var doc = new JfrDocuments.ThreadDocument(List.of(old, fresh), List.of(),
            new JfrDocuments.VirtualSummary(0, 0, 0, false, List.of(), List.of()));

        var pool = JfrThreadOverview.build(doc, T0, T0 + 60_000).pools().getFirst();

        assertEquals("worker", pool.name());
        assertEquals(3_300, pool.recordedAllocatedBytes());
        var rows = pool.threads();
        assertEquals(12_500L, rows.get(0).allocatedBytes());
        assertEquals(2_500L, rows.get(0).recordedAllocatedBytes());
        assertEquals(800L, rows.get(1).recordedAllocatedBytes());
    }

    @Test
    void groupsByPoolAndBucketsLifecycle() {
        List<JfrDocuments.PlatformThread> platform = List.of(
            thread(1, "http-nio-8080-exec-1", null, null),
            thread(2, "http-nio-8080-exec-2", null, T0 + 30_000),
            thread(3, "http-nio-8080-exec-3", T0 + 10_000, null),
            thread(4, "order-sync-1", T0 + 5_000, T0 + 50_000),
            thread(5, "order-sync-2", null, null),
            thread(6, "Attach Listener", null, null),
            thread(7, "main", null, null),
            thread(8, "lonely-7", null, null)
        );
        var stats = List.of(
            new JfrDocuments.StatPoint(T0, 6, 2, 100, 7),
            new JfrDocuments.StatPoint(T0 + 60_000, 6, 2, 102, 8));
        var doc = new JfrDocuments.ThreadDocument(platform, stats,
            new JfrDocuments.VirtualSummary(0, 0, 0, false, List.of(), List.of()));

        var overview = JfrThreadOverview.build(doc, 0, 0);

        assertEquals(60, overview.series().created().length);
        assertEquals(1000, overview.bucketMillis());
        assertEquals(2, overview.created());
        assertEquals(2, overview.ended());
        assertEquals(6, overview.aliveAtEnd());
        assertEquals(8L, overview.peakActive());

        var tomcat = overview.pools().stream().filter(p -> p.name().equals("http-nio-8080-exec")).findFirst().orElseThrow();
        assertEquals("Tomcat", tomcat.type());
        assertEquals(3, tomcat.total());
        assertEquals(1, tomcat.created());
        assertEquals(1, tomcat.ended());
        assertEquals(1, tomcat.series().created()[10]);
        assertEquals(1, tomcat.series().ended()[30]);
        assertEquals(2, tomcat.series().alive()[0]);
        assertEquals(3, tomcat.series().alive()[10]);
        assertEquals(2, tomcat.series().alive()[59]);

        var custom = overview.pools().stream().filter(p -> p.name().equals("order-sync")).findFirst().orElseThrow();
        assertEquals(2, custom.total());
        assertEquals(2, custom.peakAlive());

        var jvm = overview.pools().stream().filter(p -> p.name().equals(JfrThreadOverview.JVM_POOL)).findFirst().orElseThrow();
        assertEquals(1, jvm.total());
        var standalone = overview.pools().stream().filter(p -> p.name().equals(JfrThreadOverview.STANDALONE_POOL)).findFirst().orElseThrow();
        assertEquals(List.of("lonely-7", "main"), standalone.threads().stream().map(JfrDocuments.ThreadRow::name).toList());

        assertEquals(6L, overview.jvmActive().get(0));
        assertEquals(null, overview.jvmActive().get(30));
        int[] expectedOrderSync = new int[60];
        for (int i = 0; i < 60; i++) expectedOrderSync[i] = (i >= 5 && i < 50) ? 2 : 1;
        assertArrayEquals(expectedOrderSync, custom.series().alive());
    }
}
