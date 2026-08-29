package com.threadscope.engine.analyzer;

import com.threadscope.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class KnownIssueDetectorTest {

    private final KnownIssueDetector detector = new KnownIssueDetector();

    private static ThreadInfo thread(String name, ThreadState state, String... frames) {
        List<StackFrame> stack = java.util.Arrays.stream(frames)
            .map(f -> {
                int dot = f.lastIndexOf('.');
                return new StackFrame(f.substring(0, dot), f.substring(dot + 1), "X.java:1", 1);
            })
            .toList();
        return new ThreadInfo(name, 1, false, 5, 0, null, null, "0x1", "0x1", 1,
            state, null, null, stack, List.of(), List.of());
    }

    @Test
    void detectsHikariPoolExhaustion() {
        List<ThreadInfo> threads = IntStream.range(0, 3)
            .mapToObj(i -> thread("http-exec-" + i, ThreadState.TIMED_WAITING,
                "jdk.internal.misc.Unsafe.park",
                "com.zaxxer.hikari.pool.HikariPool.getConnection",
                "com.example.OrderDao.find"))
            .map(t -> (ThreadInfo) t)
            .toList();

        var risks = detector.detect(threads);

        assertEquals(1, risks.size());
        assertEquals("DB_POOL_EXHAUSTION", risks.getFirst().category());
        assertEquals(HealthReport.HealthLevel.CRITICAL, risks.getFirst().level());
    }

    @Test
    void belowThresholdDoesNotReport() {
        var single = thread("t1", ThreadState.TIMED_WAITING,
            "com.zaxxer.hikari.pool.HikariPool.getConnection");
        assertTrue(detector.detect(List.of(single)).isEmpty(), "低于最小命中数不报告");
    }

    @Test
    void stateConstraintIsRespected() {
        // 相同栈帧但状态是 RUNNABLE — 不满足连接池等待的状态约束
        List<ThreadInfo> threads = IntStream.range(0, 5)
            .mapToObj(i -> thread("t-" + i, ThreadState.RUNNABLE,
                "com.zaxxer.hikari.pool.HikariPool.getConnection"))
            .map(t -> (ThreadInfo) t)
            .toList();
        assertTrue(detector.detect(threads).stream()
            .noneMatch(r -> r.category().equals("DB_POOL_EXHAUSTION")));
    }

    @Test
    void detectsClassLoaderContention() {
        List<ThreadInfo> threads = IntStream.range(0, 2)
            .mapToObj(i -> thread("t-" + i, ThreadState.BLOCKED,
                "java.lang.ClassLoader.loadClass", "com.example.Service.handle"))
            .map(t -> (ThreadInfo) t)
            .toList();

        var risks = detector.detect(threads);
        assertTrue(risks.stream().anyMatch(r -> r.category().equals("CLASSLOADER_CONTENTION")));
    }
}
