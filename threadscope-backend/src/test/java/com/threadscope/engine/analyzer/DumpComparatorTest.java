package com.threadscope.engine.analyzer;

import com.threadscope.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DumpComparatorTest {

    private final DumpComparator comparator = new DumpComparator();

    private static ThreadInfo thread(String name, ThreadState state, String cpu, String... frames) {
        List<StackFrame> stack = java.util.Arrays.stream(frames)
            .map(f -> {
                int dot = f.lastIndexOf('.');
                return new StackFrame(f.substring(0, dot), f.substring(dot + 1), "X.java:1", 1);
            })
            .toList();
        return new ThreadInfo(name, 1, false, 5, 0, cpu, "100.00s", "0x1", "0x1", 1,
            state, null, null, stack, List.of(), List.of());
    }

    @Test
    void detectsStuckThreadWithUnchangedStack() {
        var stuck1 = thread("worker-1", ThreadState.RUNNABLE, "10ms",
            "com.example.Dao.slowQuery", "com.example.Service.run", "java.lang.Thread.run");
        var stuck2 = thread("worker-1", ThreadState.RUNNABLE, "20ms",
            "com.example.Dao.slowQuery", "com.example.Service.run", "java.lang.Thread.run");
        var moving1 = thread("worker-2", ThreadState.RUNNABLE, "10ms",
            "com.example.A.step1", "com.example.Service.run", "java.lang.Thread.run");
        var moving2 = thread("worker-2", ThreadState.RUNNABLE, "20ms",
            "com.example.B.step2", "com.example.Service.run", "java.lang.Thread.run");

        var result = comparator.compare(
            List.of(List.of(stuck1, moving1), List.of(stuck2, moving2)),
            List.of("10:00:00", "10:00:05"));

        assertEquals(1, result.stuckThreads().size());
        assertEquals("worker-1", result.stuckThreads().getFirst().threadName());
    }

    @Test
    void idleParkedPoolWorkerIsNotStuck() {
        var idle = thread("pool-1-thread-1", ThreadState.WAITING, "5ms",
            "jdk.internal.misc.Unsafe.park",
            "java.util.concurrent.LinkedBlockingQueue.take",
            "java.lang.Thread.run");

        var result = comparator.compare(
            List.of(List.of(idle), List.of(idle)),
            List.of("10:00:00", "10:00:05"));

        assertTrue(result.stuckThreads().isEmpty(), "池内空闲 park 不是卡死");
    }

    @Test
    void blockedThreadWithSameStackIsAlwaysStuck() {
        var blocked = thread("worker-b", ThreadState.BLOCKED, "5ms",
            "com.example.Service.sync", "java.lang.Thread.run", "java.lang.Thread.run0");

        var result = comparator.compare(
            List.of(List.of(blocked), List.of(blocked)),
            java.util.Arrays.asList(null, null));

        assertEquals(1, result.stuckThreads().size());
    }

    @Test
    void computesCpuDeltaBetweenFirstAndLastDump() {
        var busy1 = thread("busy", ThreadState.RUNNABLE, "1000.00ms", "com.example.Hot.loop", "a.B.c", "d.E.f");
        var busy2 = thread("busy", ThreadState.RUNNABLE, "9000.00ms", "com.example.Hot.loop2", "a.B.c", "d.E.f");
        var calm1 = thread("calm", ThreadState.WAITING, "100.00ms", "a.B.c", "d.E.f", "g.H.i");
        var calm2 = thread("calm", ThreadState.WAITING, "100.00ms", "a.B.x", "d.E.f", "g.H.i");

        var result = comparator.compare(
            List.of(List.of(busy1, calm1), List.of(busy2, calm2)),
            java.util.Arrays.asList(null, null));

        assertEquals(1, result.topCpuThreads().size(), "cpu 无增量的线程不应上榜");
        var top = result.topCpuThreads().getFirst();
        assertEquals("busy", top.threadName());
        assertEquals(8000.0, top.cpuMsDelta(), 0.01);
    }

    @Test
    void detectsThreadCountTrend() {
        List<ThreadInfo> dump1 = List.of(
            thread("grow-1", ThreadState.RUNNABLE, "1ms", "a.B.c", "d.E.f", "g.H.i"));
        List<ThreadInfo> dump2 = List.of(
            thread("grow-1", ThreadState.RUNNABLE, "1ms", "a.B.c", "d.E.f", "g.H.i"),
            thread("grow-2", ThreadState.RUNNABLE, "1ms", "a.B.c", "d.E.f", "g.H.i"),
            thread("grow-3", ThreadState.RUNNABLE, "1ms", "a.B.c", "d.E.f", "g.H.i"),
            thread("grow-4", ThreadState.RUNNABLE, "1ms", "a.B.c", "d.E.f", "g.H.i"));

        var result = comparator.compare(List.of(dump1, dump2), java.util.Arrays.asList(null, null));

        assertEquals(1, result.threadTrends().size());
        assertEquals("grow", result.threadTrends().getFirst().groupName());
        assertEquals(List.of(1, 4), result.threadTrends().getFirst().counts());
    }

    @Test
    void parsesCpuTimeFormats() {
        assertEquals(125.4, DumpComparator.parseCpuMs("125.40ms"), 0.01);
        assertEquals(1500.0, DumpComparator.parseCpuMs("1.5s"), 0.01);
        assertEquals(-1, DumpComparator.parseCpuMs(null));
        assertEquals(-1, DumpComparator.parseCpuMs("garbage"));
    }
}
