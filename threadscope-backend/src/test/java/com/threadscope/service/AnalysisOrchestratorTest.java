package com.threadscope.service;

import com.threadscope.config.AnalysisProperties;
import com.threadscope.model.AnalysisResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisOrchestratorTest {

    private final AnalysisOrchestrator orchestrator =
        new AnalysisOrchestrator(new AnalysisProperties(50_000, 30, 150_000, 60));

    private static String dump(String extraThread, String cpu) {
        return "Full thread dump Java HotSpot(TM) 64-Bit Server VM (21+35 mixed mode):\n\n" +
               "\"main\" #1 prio=5 os_prio=0 cpu=" + cpu + " elapsed=100.00s tid=0x1 nid=0x1a0 runnable [0x00007f0000000000]\n" +
               "   java.lang.Thread.State: RUNNABLE\n" +
               "\tat com.example.Main.work(Main.java:10)\n" +
               "\tat com.example.Main.main(Main.java:5)\n\n" +
               (extraThread != null
                   ? "\"" + extraThread + "\" #2 prio=5 os_prio=0 cpu=1.00ms elapsed=10.00s tid=0x2 nid=0x1a1 runnable [0x00007f0000001000]\n" +
                     "   java.lang.Thread.State: RUNNABLE\n" +
                     "\tat com.example.Extra.run(Extra.java:1)\n\n"
                   : "");
    }

    @Test
    void singleDumpHasNoComparison() throws IOException {
        AnalysisResult result = orchestrator.analyzeFromText("id-1", "single.txt", dump(null, "10.00ms"));

        assertNull(result.comparison());
        assertEquals(1, result.dumpCount());
        assertEquals(1, result.totalThreads());
    }

    @Test
    void multiDumpFileProducesComparisonAndAnalyzesLastDump() throws IOException {
        String content =
            "2026-08-29 10:00:00\n" + dump(null, "10.00ms") +
            "2026-08-29 10:00:05\n" + dump("late-arrival", "5000.00ms");

        AnalysisResult result = orchestrator.analyzeFromText("id-2", "multi.txt", content);

        assertNotNull(result.comparison());
        assertEquals(2, result.dumpCount());
        // 主分析基于最后一份 dump — 包含 late-arrival 线程
        assertEquals(2, result.totalThreads());
        assertTrue(result.threads().stream().anyMatch(t -> t.name().equals("late-arrival")));

        // main 线程 CPU 从 10ms 涨到 5000ms，应出现在 CPU 差值榜
        assertFalse(result.comparison().topCpuThreads().isEmpty());
        assertEquals("main", result.comparison().topCpuThreads().getFirst().threadName());

        // main 线程两次栈相同 → 栈不动检测命中 (栈深 >= 3 才有指纹意义，此处只有 2 帧则不命中也合理)
        assertEquals(2, result.comparison().snapshots().size());
        assertEquals("2026-08-29 10:00:00", result.comparison().snapshots().getFirst().timestamp());
    }

    @Test
    void multipleContentsAreComparedInOrder() throws IOException {
        AnalysisResult result = orchestrator.analyzeContents("id-3", "a.txt, b.txt",
            List.of(dump(null, "10.00ms"), dump("newcomer", "20.00ms")));

        assertNotNull(result.comparison());
        assertEquals(2, result.comparison().dumpCount());
        assertEquals(1, result.comparison().snapshots().get(0).totalThreads());
        assertEquals(2, result.comparison().snapshots().get(1).totalThreads());
    }
}
