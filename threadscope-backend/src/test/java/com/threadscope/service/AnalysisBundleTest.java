package com.threadscope.service;

import com.threadscope.config.AnalysisProperties;
import com.threadscope.model.AnalysisBundle;
import com.threadscope.model.DataQualityIssue;
import com.threadscope.model.ThreadKind;
import com.threadscope.service.AnalysisOrchestrator.AnalysisOptions;
import com.threadscope.service.AnalysisOrchestrator.DumpSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisBundleTest {

    private final AnalysisOrchestrator orchestrator = new AnalysisOrchestrator(AnalysisProperties.defaults());

    private static final String HEADER = "Full thread dump OpenJDK 64-Bit Server VM (21.0.2+13-58 mixed mode):\n\n";

    private static final String DEADLOCK_DUMP = HEADER +
        "\"A\" #10 prio=5 os_prio=0 cpu=1.00ms elapsed=50.00s tid=0xa nid=0x10 waiting for monitor entry [0x0]\n" +
        "   java.lang.Thread.State: BLOCKED (on object monitor)\n" +
        "\tat com.acme.Bank.transfer(Bank.java:10)\n" +
        "\t- waiting to lock <0x0000000000000002> (a java.lang.Object)\n" +
        "\t- locked <0x0000000000000001> (a java.lang.Object)\n" +
        "\tat java.lang.Thread.run(Thread.java:1583)\n\n" +
        "\"B\" #11 prio=5 os_prio=0 cpu=1.00ms elapsed=50.00s tid=0xb nid=0x11 waiting for monitor entry [0x0]\n" +
        "   java.lang.Thread.State: BLOCKED (on object monitor)\n" +
        "\tat com.acme.Bank.transfer(Bank.java:10)\n" +
        "\t- waiting to lock <0x0000000000000001> (a java.lang.Object)\n" +
        "\t- locked <0x0000000000000002> (a java.lang.Object)\n" +
        "\tat java.lang.Thread.run(Thread.java:1583)\n\n";

    private static final String CALM_DUMP = HEADER +
        "\"A\" #10 prio=5 os_prio=0 cpu=2.00ms elapsed=55.00s tid=0xa nid=0x10 runnable [0x0]\n" +
        "   java.lang.Thread.State: RUNNABLE\n" +
        "\tat com.acme.Bank.audit(Bank.java:20)\n" +
        "\tat java.lang.Thread.run(Thread.java:1583)\n\n" +
        "\"ForkJoinPool-1-worker-1\" #22 [99] daemon prio=5 os_prio=0 cpu=5.00ms elapsed=55.00s tid=0xc nid=99 runnable [0x0]\n" +
        "   java.lang.Thread.State: RUNNABLE\n" +
        "   Carrying virtual thread #31\n" +
        "\tat com.acme.Job.compute(Job.java:5)\n" +
        "\tat jdk.internal.vm.Continuation.enterSpecial(java.base@21/Continuation.java)\n" +
        "\tat jdk.internal.vm.Continuation.run(java.base@21/Continuation.java:248)\n\n";

    @Test
    void everySnapshotIsFullyAnalyzed() throws IOException {
        AnalysisBundle bundle = orchestrator.analyzeBundle("base", "dumps",
            List.of(DumpSource.utf8("1.txt", DEADLOCK_DUMP), DumpSource.utf8("2.txt", CALM_DUMP)),
            AnalysisOptions.defaults());

        assertEquals(2, bundle.snapshots().size());
        assertEquals("base~s0", bundle.snapshots().get(0).analysisId());
        assertEquals("base", bundle.latest().analysisId());
        assertTrue(bundle.snapshots().get(0).deadlocks().hasDeadlocks(), "死锁只出现在第一份快照中也必须被分析出来");
        assertFalse(bundle.latest().deadlocks().hasDeadlocks());
        assertSame(bundle.snapshots().get(0).comparison(), bundle.latest().comparison());
    }

    @Test
    void reportsDataQualityAndCarrierThreads() throws IOException {
        AnalysisBundle bundle = orchestrator.analyzeBundle("q", "calm.txt",
            List.of(DumpSource.utf8("calm.txt", CALM_DUMP)), AnalysisOptions.defaults());
        var result = bundle.latest();

        List<String> codes = result.dataQuality().stream().map(DataQualityIssue::code).toList();
        assertTrue(codes.contains("NO_LOCKED_SYNCHRONIZERS"));
        assertTrue(codes.contains("SINGLE_DUMP"));
        assertTrue(codes.contains("VIRTUAL_THREADS_HIDDEN"));

        var carrier = result.threads().stream().filter(t -> t.name().startsWith("ForkJoinPool")).findFirst().orElseThrow();
        assertEquals(ThreadKind.CARRIER, carrier.kind());
        assertEquals("#31", carrier.carrier());
        assertEquals("#22", carrier.key());
        assertEquals(1, result.virtualThreads().mountedCount());
    }

    @Test
    void businessPackagesAreAppliedAndSuggested() throws IOException {
        AnalysisBundle bundle = orchestrator.analyzeBundle("p", "calm.txt",
            List.of(DumpSource.utf8("calm.txt", CALM_DUMP)), new AnalysisOptions(List.of(), "order-service"));
        assertEquals("order-service", bundle.latest().applicationName());
        assertTrue(bundle.latest().suggestedBusinessPackages().contains("com.acme"));
    }
}
