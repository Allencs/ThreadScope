package com.threadscope.engine.analyzer;

import com.threadscope.model.ThreadKind;
import com.threadscope.model.ThreadState;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static com.threadscope.engine.analyzer.TestThreads.thread;
import static org.junit.jupiter.api.Assertions.*;

class DumpComparatorIdentityTest {

    private final DumpComparator comparator = new DumpComparator();

    @Test
    void sameNameThreadsAreMatchedByIdentity() {
        // 两个同名 worker：#10 一直卡住，#11 在正常推进
        var stuck1 = thread("worker", "#10", ThreadState.RUNNABLE, "100.00s", "10ms", List.of(), ThreadKind.PLATFORM,
            "com.acme.Dao.slow", "com.acme.Svc.run", "java.lang.Thread.run");
        var moving1 = thread("worker", "#11", ThreadState.RUNNABLE, "100.00s", "10ms", List.of(), ThreadKind.PLATFORM,
            "com.acme.A.step1", "com.acme.Svc.run", "java.lang.Thread.run");
        var stuck2 = thread("worker", "#10", ThreadState.RUNNABLE, "105.00s", "20ms", List.of(), ThreadKind.PLATFORM,
            "com.acme.Dao.slow", "com.acme.Svc.run", "java.lang.Thread.run");
        var moving2 = thread("worker", "#11", ThreadState.RUNNABLE, "105.00s", "900ms", List.of(), ThreadKind.PLATFORM,
            "com.acme.B.step2", "com.acme.Svc.run", "java.lang.Thread.run");

        var result = comparator.compare(List.of(List.of(stuck1, moving1), List.of(moving2, stuck2)),
            Arrays.asList(null, null));

        assertFalse(result.jvmRestartSuspected());
        assertEquals(1, result.stuckThreads().size());
        assertEquals("#10", result.stuckThreads().getFirst().threadKey());
        assertEquals("#11", result.topCpuThreads().getFirst().threadKey());
        assertEquals(890.0, result.topCpuThreads().getFirst().cpuMsDelta(), 0.01);
    }

    @Test
    void jvmRestartDisablesCrossDumpConclusions() {
        var before = thread("main", "#1", ThreadState.RUNNABLE, "3600.00s", "5000ms", List.of(), ThreadKind.PLATFORM,
            "com.acme.Main.loop", "com.acme.Main.run", "com.acme.Main.main");
        var after = thread("main", "#1", ThreadState.RUNNABLE, "12.00s", "100ms", List.of(), ThreadKind.PLATFORM,
            "com.acme.Main.loop", "com.acme.Main.run", "com.acme.Main.main");

        var result = comparator.compare(List.of(List.of(before), List.of(after)), Arrays.asList(null, null));

        assertTrue(result.jvmRestartSuspected());
        assertTrue(result.stuckThreads().isEmpty());
        assertTrue(result.topCpuThreads().isEmpty());
    }

    @Test
    void threadWaitingOnResourceWithFrozenStackIsStuck() {
        String[] frames = {
            "jdk.internal.misc.Unsafe.park",
            "java.util.concurrent.locks.LockSupport.park",
            "java.util.concurrent.FutureTask.awaitDone",
            "java.util.concurrent.FutureTask.get",
            "com.acme.Report.generate",
            "java.util.concurrent.ThreadPoolExecutor.runWorker",
            "java.lang.Thread.run"
        };
        var t = thread("report-1", ThreadState.WAITING, frames);
        var result = comparator.compare(List.of(List.of(t), List.of(t)), Arrays.asList(null, null));
        assertEquals(1, result.stuckThreads().size(), "任务中无限期等待 Future 不是池内空闲");
    }
}
