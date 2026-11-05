package com.threadscope.engine.analyzer;

import com.threadscope.model.LockAction;
import com.threadscope.model.ThreadKind;
import com.threadscope.model.ThreadState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.threadscope.engine.analyzer.TestThreads.thread;
import static org.junit.jupiter.api.Assertions.*;

class VirtualThreadAnalyzerTest {

    private final VirtualThreadAnalyzer analyzer = new VirtualThreadAnalyzer();

    private static com.threadscope.model.ThreadInfo pinnedVirtual(String key) {
        return thread("VirtualThread[" + key + "]", key, ThreadState.WAITING, null, null,
            List.of(new LockAction.Held("java.lang.Object@1f", "java.lang.Object", 4)),
            ThreadKind.VIRTUAL,
            "jdk.internal.misc.Unsafe.park",
            "java.lang.VirtualThread.parkOnCarrierThread",
            "java.lang.VirtualThread.park",
            "java.util.concurrent.locks.LockSupport.park",
            "com.acme.Cache.refresh",
            "java.lang.VirtualThread.run");
    }

    @Test
    void detectsPinningWhileHoldingMonitor() {
        var report = analyzer.analyze(List.of(pinnedVirtual("#30")), 21, true);
        assertEquals(1, report.virtualCount());
        assertEquals(1, report.pinnedCount());
        var pinned = report.pinned().getFirst();
        assertTrue(pinned.reason().contains("synchronized"));
        assertEquals("com.acme.Cache.refresh", pinned.blockingFrame());
    }

    @Test
    void blockedOnMonitorPinsOnlyBeforeJdk24() {
        var carrier = thread("ForkJoinPool-1-worker-1", "#22", ThreadState.BLOCKED, null, null,
            List.of(new LockAction.WaitingToLock("0x1", "java.lang.Object", 0)),
            ThreadKind.CARRIER,
            "com.acme.Account.debit",
            "jdk.internal.vm.Continuation.enterSpecial",
            "jdk.internal.vm.Continuation.run",
            "java.lang.VirtualThread.runContinuation");

        assertEquals(1, analyzer.analyze(List.of(carrier), 21, false).pinnedCount());
        assertEquals(0, analyzer.analyze(List.of(carrier), 24, false).pinnedCount(), "JEP 491 后 synchronized 不再钉住载体");
    }

    @Test
    void allCarriersPinnedIsCritical() {
        var carrier = thread("ForkJoinPool-1-worker-1", "#22", ThreadState.WAITING, null, null, List.of(),
            ThreadKind.CARRIER,
            "jdk.internal.misc.Unsafe.park",
            "java.lang.VirtualThread.parkOnCarrierThread",
            "com.acme.Native.call",
            "jdk.internal.vm.Continuation.run");
        var report = analyzer.analyze(List.of(carrier), 21, false);
        var health = new HealthChecker().check(List.of(carrier), null, List.of(), List.of(), report,
            CodeLayerClassifier.inferring());
        var risk = health.risks().stream().filter(r -> r.category().equals("VIRTUAL_THREAD_PINNED")).findFirst();
        assertTrue(risk.isPresent());
        assertEquals(com.threadscope.model.HealthReport.HealthLevel.CRITICAL, risk.get().level());
    }
}
