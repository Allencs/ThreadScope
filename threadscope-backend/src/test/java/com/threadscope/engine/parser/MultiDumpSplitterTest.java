package com.threadscope.engine.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultiDumpSplitterTest {

    private final MultiDumpSplitter splitter = new MultiDumpSplitter();

    private static String dump(String threadName) {
        return "Full thread dump Java HotSpot(TM) 64-Bit Server VM (21+35 mixed mode):\n\n" +
               "\"" + threadName + "\" #1 prio=5 os_prio=0 tid=0x1 nid=0x1a3 runnable [0x00007f0000000000]\n" +
               "   java.lang.Thread.State: RUNNABLE\n" +
               "\tat com.example.Foo.bar(Foo.java:1)\n\n";
    }

    @Test
    void singleDumpReturnsOneSegment() {
        List<MultiDumpSplitter.DumpSegment> segments = splitter.split(dump("t1"));
        assertEquals(1, segments.size());
    }

    @Test
    void contentWithoutDumpHeaderIsOneSegment() {
        String content = "\"t1\" #1 prio=5 os_prio=0 tid=0x1 nid=0x1 runnable\n";
        List<MultiDumpSplitter.DumpSegment> segments = splitter.split(content);
        assertEquals(1, segments.size());
        assertEquals(content, segments.getFirst().content());
    }

    @Test
    void splitsConsecutiveDumpsAndCapturesTimestamps() {
        String content =
            "2026-08-29 10:00:00\n" + dump("t1") +
            "2026-08-29 10:00:05\n" + dump("t2") +
            "2026-08-29 10:00:10\n" + dump("t3");

        List<MultiDumpSplitter.DumpSegment> segments = splitter.split(content);

        assertEquals(3, segments.size());
        assertEquals("2026-08-29 10:00:00", segments.get(0).timestamp());
        assertEquals("2026-08-29 10:00:05", segments.get(1).timestamp());
        assertEquals("2026-08-29 10:00:10", segments.get(2).timestamp());
        assertTrue(segments.get(0).content().contains("\"t1\""));
        assertFalse(segments.get(0).content().contains("\"t2\""), "段与段之间不能串线程");
        assertTrue(segments.get(2).content().contains("\"t3\""));
    }

    @Test
    void splitsDumpsWithoutTimestamps() {
        List<MultiDumpSplitter.DumpSegment> segments = splitter.split(dump("t1") + dump("t2"));
        assertEquals(2, segments.size());
        assertNull(segments.get(0).timestamp());
    }
}
