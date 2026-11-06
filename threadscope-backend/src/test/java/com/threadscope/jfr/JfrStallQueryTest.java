package com.threadscope.jfr;

import com.threadscope.engine.analyzer.CodeLayerClassifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JfrStallQueryTest {

    private static final long START = 1_000_000L;
    private static final CodeLayerClassifier ACME = new CodeLayerClassifier(List.of("com.acme"));

    /**
     * 0: HashMap.resize ← OrderService.load ← Spring
     * 1: String.getBytes ← OrderService.load
     * 2: Netty 线程，整条栈没有业务代码
     */
    private static final List<String> FRAMES = List.of(
        "java.util.HashMap#resize()",
        "com.acme.OrderService#load(long)",
        "org.springframework.web.servlet.DispatcherServlet#doDispatch(HttpServletRequest, HttpServletResponse)",
        "java.lang.String#getBytes()",
        "io.netty.channel.nio.NioEventLoop#run()");

    private static final List<JfrDocuments.StallStack> STACKS = List.of(
        new JfrDocuments.StallStack(List.of(0, 1, 2), false),
        new JfrDocuments.StallStack(List.of(3, 1), false),
        new JfrDocuments.StallStack(List.of(4), false));

    private static JfrDocuments.MemoryDocument doc(List<JfrDocuments.StallEvent> stalls) {
        var gc = new JfrDocuments.GcDocument("ZGC Minor", "ZGC Major", null, null, List.of(), List.of(),
            stalls.size(), stalls, List.of(), List.of(), List.of(), null, FRAMES, STACKS);
        return new JfrDocuments.MemoryDocument(START, START + 10_000, 10, List.of(), null, null, gc);
    }

    private static JfrDocuments.StallEvent stall(long offsetMs, long millis, String thread, Integer stack) {
        return new JfrDocuments.StallEvent(START + offsetMs, millis * 1_000_000, thread, "Small", 2L << 20, stack);
    }

    private static final List<JfrDocuments.StallEvent> EVENTS = List.of(
        stall(500, 40, "tomcat-exec-1", 0),
        stall(1500, 20, "tomcat-exec-2", 1),
        stall(5500, 30, "tomcat-exec-1", 0),
        stall(8500, 10, "nioEventLoop-1", 2));

    @Test
    void businessGroupingMergesStacksByFirstBusinessFrame() {
        var report = JfrStallQuery.report(doc(EVENTS), JfrStallQuery.BUSINESS, null, null, ACME);
        assertTrue(report.stacksAvailable());
        assertEquals(4, report.count());
        assertEquals(100_000_000L, report.durationNanos());
        assertEquals(2, report.groupCount());

        var load = report.groups().get(0);
        assertEquals("com.acme.OrderService#load(long)", load.key());
        assertTrue(load.business());
        assertEquals(3, load.count());
        assertEquals(90_000_000L, load.durationNanos());
        assertEquals(2, load.stackCount());
        assertEquals(2, load.threadCount());
        assertEquals("tomcat-exec-1", load.threads().get(0).name());
        assertEquals(90.0, load.percent(), 0.01);
        // 耗时最多的那条栈排在前面，业务帧位置正确
        assertEquals(1, load.traces().get(0).anchor());
        assertEquals("HashMap.resize()", load.traces().get(0).frames().get(0).label());
        assertEquals(40_000_000L, load.events().get(0).durationNanos());

        var netty = report.groups().get(1);
        assertFalse(netty.business());
        assertEquals("io.netty.channel.nio.NioEventLoop#run()", netty.key());
        assertNull(netty.traces().get(0).anchor());
    }

    @Test
    void stackGroupingKeepsEveryStackApart() {
        var report = JfrStallQuery.report(doc(EVENTS), JfrStallQuery.STACK, null, null, ACME);
        assertEquals(3, report.groupCount());
        assertEquals("HashMap.resize()", report.groups().get(0).label());
        assertEquals(2, report.groups().get(0).count());
        assertEquals(1, report.groups().get(0).stackCount());
    }

    @Test
    void threadAndRangeFiltersApply() {
        var tomcat = JfrStallQuery.report(doc(EVENTS), JfrStallQuery.BUSINESS, "TOMCAT", null, ACME);
        assertEquals(3, tomcat.count());
        assertEquals(1, tomcat.groupCount());

        var firstSecond = JfrStallQuery.report(doc(EVENTS), JfrStallQuery.BUSINESS, null,
            JfrDocuments.BucketRange.of(0, 2, 10), ACME);
        assertEquals(2, firstSecond.count());
        assertEquals(0, firstSecond.rangeFrom());
        assertEquals(2, firstSecond.rangeTo());
    }

    @Test
    void legacyEventsWithoutStacksStillCount() {
        var legacy = new JfrDocuments.GcDocument(null, null, null, null, List.of(), List.of(), 1,
            List.of(stall(100, 5, "t", null)), List.of(), List.of(), List.of(), null, null, null);
        var report = JfrStallQuery.report(new JfrDocuments.MemoryDocument(START, START + 10_000, 10, List.of(), null, null, legacy),
            JfrStallQuery.BUSINESS, null, null, ACME);
        assertFalse(report.stacksAvailable());
        assertEquals(1, report.count());
        assertEquals("-", report.groups().get(0).key());
        assertTrue(report.groups().get(0).traces().isEmpty());
    }
}
