package com.threadscope.engine.analyzer;

import com.threadscope.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TopHCorrelatorTest {

    private final TopHCorrelator correlator = new TopHCorrelator();

    private static ThreadInfo thread(String name, long nidDecimal) {
        return new ThreadInfo(name, 1, false, 5, 0, null, null, "0x1",
            "0x" + Long.toHexString(nidDecimal), nidDecimal,
            ThreadState.RUNNABLE, null, null,
            List.of(new StackFrame("com.example.Hot", "loop", "Hot.java:1", 1)),
            List.of(), List.of());
    }

    @Test
    void correlatesTopOutputWithThreadsByNid() {
        String topOutput = """
            top - 10:00:00 up 1 day,  1 user,  load average: 3.0, 2.5, 2.0
            Threads: 200 total,   3 running, 197 sleeping
                PID USER      PR  NI    VIRT    RES    SHR S  %CPU  %MEM     TIME+ COMMAND
                419 app       20   0 8912345 456789  12345 R  93.8   5.6   1:23.45 java
                420 app       20   0 8912345 456789  12345 S   2.1   5.6   0:03.45 java
                999 app       20   0 8912345 456789  12345 S   0.5   5.6   0:00.45 java
            """;
        // 419 = 0x1a3, 420 = 0x1a4；999 在 dump 中不存在
        var result = correlator.correlate(topOutput,
            List.of(thread("busy-worker", 419), thread("calm-worker", 420)));

        assertEquals(3, result.parsedEntries());
        assertEquals(2, result.matched());
        assertEquals("busy-worker", result.threads().getFirst().threadName());
        assertEquals(93.8, result.threads().getFirst().cpuPercent(), 0.01);
        assertEquals("com.example.Hot.loop", result.threads().getFirst().topMethod());
    }

    @Test
    void garbageInputYieldsEmptyResult() {
        var result = correlator.correlate("not a top output\nat all",
            List.of(thread("t", 419)));
        assertEquals(0, result.parsedEntries());
        assertTrue(result.threads().isEmpty());
    }
}
