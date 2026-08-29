package com.threadscope.engine.analyzer;

import com.threadscope.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockChainAnalyzerTest {

    private final BlockChainAnalyzer analyzer = new BlockChainAnalyzer();

    private static ThreadInfo thread(String name, List<LockAction> locks) {
        return new ThreadInfo(name, 1, false, 5, 0, null, null, "0x1", "0x1", 1,
            ThreadState.BLOCKED, null, null, List.of(), locks, List.of());
    }

    @Test
    void findsRootOfTransitiveBlockChain() {
        // C (RUNNABLE 持有 L2) ← B (持有 L1 等 L2) ← 5 个 waiter 等 L1
        var rootC = thread("C", List.of(new LockAction.Held("0xL2", "java.lang.Object", 0)));
        var middleB = thread("B", List.of(
            new LockAction.Held("0xL1", "java.lang.Object", 0),
            new LockAction.WaitingToLock("0xL2", "java.lang.Object", 0)));
        List<ThreadInfo> waiters = java.util.stream.IntStream.range(0, 5)
            .mapToObj(i -> thread("waiter-" + i,
                List.of(new LockAction.WaitingToLock("0xL1", "java.lang.Object", 0))))
            .toList();

        List<ThreadInfo> all = new java.util.ArrayList<>(List.of(rootC, middleB));
        all.addAll(waiters);

        var roots = analyzer.findRootBlockers(all);

        assertEquals(1, roots.size(), "只有 C 是根 — B 自己也在等锁，不是根");
        var root = roots.getFirst();
        assertEquals("C", root.rootThreadName());
        assertEquals(1, root.directBlocked());
        assertEquals(6, root.totalBlocked(), "B + 5 个 waiter 都被 C 传递拖住");
        assertEquals(2, root.maxChainDepth());
    }

    @Test
    void deadlockCycleProducesNoRoot() {
        // A↔B 互等 — 双方都在等锁，没有根；该场景由 DeadlockDetector 负责
        var a = thread("A", List.of(
            new LockAction.Held("0xL1", "java.lang.Object", 0),
            new LockAction.WaitingToLock("0xL2", "java.lang.Object", 0)));
        var b = thread("B", List.of(
            new LockAction.Held("0xL2", "java.lang.Object", 0),
            new LockAction.WaitingToLock("0xL1", "java.lang.Object", 0)));

        assertTrue(analyzer.findRootBlockers(List.of(a, b)).isEmpty());
    }

    @Test
    void noBlockingProducesNoRoots() {
        var idle = thread("idle", List.of());
        assertTrue(analyzer.findRootBlockers(List.of(idle)).isEmpty());
    }
}
