package com.threadscope.engine.analyzer;

import com.threadscope.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeadlockDetectorTest {

    private final DeadlockDetector detector = new DeadlockDetector();

    private static ThreadInfo thread(String name, List<LockAction> lockActions) {
        return new ThreadInfo(name, 1, false, 5, 0, null, null, "0x1", "0x1", 1,
            ThreadState.BLOCKED, null, null, List.of(), lockActions, List.of());
    }

    @Test
    void detectsTwoThreadCycleByGraphAnalysis() {
        var threadA = thread("A", List.of(
            new LockAction.Held("0xL1", "java.lang.Object", 0),
            new LockAction.WaitingToLock("0xL2", "java.lang.Object", 0)));
        var threadB = thread("B", List.of(
            new LockAction.Held("0xL2", "java.lang.Object", 0),
            new LockAction.WaitingToLock("0xL1", "java.lang.Object", 0)));

        DeadlockInfo info = detector.detect(List.of(threadA, threadB), null);

        assertEquals(1, info.totalDeadlocks());
        assertTrue(info.chains().getFirst().threadNames().containsAll(List.of("A", "B")));
    }

    @Test
    void detectsCycleReachedThroughNonCycleChain() {
        // D → A → B → A：D 在环外，环为 A↔B。
        // 回归三色 DFS：从 D 出发的探索不能导致环被漏检或误报 D 在环内。
        var threadD = thread("D", List.of(
            new LockAction.WaitingToLock("0xL1", "java.lang.Object", 0)));
        var threadA = thread("A", List.of(
            new LockAction.Held("0xL1", "java.lang.Object", 0),
            new LockAction.WaitingToLock("0xL2", "java.lang.Object", 0)));
        var threadB = thread("B", List.of(
            new LockAction.Held("0xL2", "java.lang.Object", 0),
            new LockAction.WaitingToLock("0xL1", "java.lang.Object", 0)));

        DeadlockInfo info = detector.detect(List.of(threadD, threadA, threadB), null);

        assertEquals(1, info.totalDeadlocks());
        List<String> cycle = info.chains().getFirst().threadNames();
        assertTrue(cycle.containsAll(List.of("A", "B")));
        assertFalse(cycle.contains("D"), "环外线程不应出现在死锁链中");
    }

    @Test
    void parkingThreadsDoNotProduceFalsePositives() {
        // parking to wait for 不构成"持有并等待"，不应参与死锁判定
        var threadA = thread("A", List.of(
            new LockAction.Held("0xL1", "java.lang.Object", 0),
            new LockAction.ParkingToWaitFor("0xL2", "j.u.c.l.ReentrantLock", 0)));
        var threadB = thread("B", List.of(
            new LockAction.Held("0xL2", "j.u.c.l.ReentrantLock", 0),
            new LockAction.ParkingToWaitFor("0xL1", "java.lang.Object", 0)));

        DeadlockInfo info = detector.detect(List.of(threadA, threadB), null);
        assertEquals(0, info.totalDeadlocks());
    }

    @Test
    void splitsMultipleJvmReportedDeadlocksIntoSeparateChains() {
        List<String> section = List.of(
            "Found one Java-level deadlock:",
            "=============================",
            "\"Thread-1\":",
            "  waiting to lock monitor 0x00007f8a2c006168 (object 0x000000076cd440b8, a java.lang.Object),",
            "  which is held by \"Thread-2\"",
            "\"Thread-2\":",
            "  waiting to lock monitor 0x00007f8a2c005fa8 (object 0x000000076bc330a0, a java.lang.Object),",
            "  which is held by \"Thread-1\"",
            "",
            "Found one Java-level deadlock:",
            "=============================",
            "\"Thread-3\":",
            "  waiting to lock monitor 0x00007f8a2c007000 (object 0x000000076dd00000, a java.lang.Object),",
            "  which is held by \"Thread-4\"",
            "\"Thread-4\":",
            "  waiting to lock monitor 0x00007f8a2c008000 (object 0x000000076ee00000, a java.lang.Object),",
            "  which is held by \"Thread-3\""
        );

        DeadlockInfo info = detector.detect(List.of(), section);

        assertEquals(2, info.totalDeadlocks(), "两段 JVM 死锁不能被合并成一条链");
        assertEquals(List.of("Thread-1", "Thread-2"), info.chains().get(0).threadNames());
        assertEquals(List.of("Thread-3", "Thread-4"), info.chains().get(1).threadNames());
    }

    @Test
    void deduplicatesJvmAndGraphDetectedChains() {
        var threadA = thread("A", List.of(
            new LockAction.Held("0xL1", "java.lang.Object", 0),
            new LockAction.WaitingToLock("0xL2", "java.lang.Object", 0)));
        var threadB = thread("B", List.of(
            new LockAction.Held("0xL2", "java.lang.Object", 0),
            new LockAction.WaitingToLock("0xL1", "java.lang.Object", 0)));
        List<String> section = List.of(
            "Found one Java-level deadlock:",
            "\"A\":",
            "  waiting to lock monitor 0x1 (object 0xL2, a java.lang.Object),",
            "\"B\":",
            "  waiting to lock monitor 0x2 (object 0xL1, a java.lang.Object),"
        );

        DeadlockInfo info = detector.detect(List.of(threadA, threadB), section);

        assertEquals(1, info.totalDeadlocks(), "JVM 报告与图检测发现的同一死锁应去重");
    }
}
