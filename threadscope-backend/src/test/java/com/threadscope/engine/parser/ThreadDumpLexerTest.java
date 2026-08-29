package com.threadscope.engine.parser;

import com.threadscope.exception.DumpTooLargeException;
import com.threadscope.model.ThreadState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ThreadDumpLexerTest {

    private final ThreadDumpLexer lexer = new ThreadDumpLexer();
    private final ThreadSemanticParser parser = new ThreadSemanticParser();

    private static String threadBlock(String name, String nid) {
        return "\"" + name + "\" #1 prio=5 os_prio=0 tid=0x1 nid=" + nid + " runnable [0x00007f0000000000]\n" +
               "   java.lang.Thread.State: RUNNABLE\n" +
               "\tat com.example.Foo.bar(Foo.java:1)\n\n";
    }

    @Test
    void parsesSampleDumpEndToEnd() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/sample-dumps/deadlock-sample.txt")) {
            assertNotNull(in, "样例 dump 缺失");
            ThreadDumpLexer.LexerResult result = lexer.tokenize(in);

            assertNotNull(result.jvmVersion());
            assertTrue(result.threadBlocks().size() >= 8, "线程块数量: " + result.threadBlocks().size());
            assertFalse(result.deadlockSection().isEmpty(), "死锁段应被捕获");

            // 语义解析冒烟：每个块都能解析且有名字
            for (var block : result.threadBlocks()) {
                var info = parser.parse(block);
                assertNotNull(info.name());
                assertFalse(info.name().isBlank());
            }
        }
    }

    @Test
    void smrSectionEndsAtBlankLine() throws IOException {
        String dump = """
            Threads class SMR info:
            _java_thread_list=0x00007f0000000001, length=2, elements={
            0x00007f0000000002, 0x00007f0000000003

            """ + threadBlock("worker-1", "0x1a3");
        var result = lexer.tokenize(dump);
        assertEquals(1, result.threadBlocks().size(), "空行后必须退出 SMR 段，不能吞掉后续线程");
    }

    @Test
    void smrSectionWithoutClosingBraceStillYieldsThreads() throws IOException {
        // 无 "}"、无空行，SMR 段直接跟线程头 — 依赖引号回退逻辑
        String dump = "Threads class SMR info:\n" +
                      "_java_thread_list=0x00007f0000000001, length=1, elements=\n" +
                      threadBlock("worker-2", "0x1a4");
        var result = lexer.tokenize(dump);
        assertEquals(1, result.threadBlocks().size());
        assertEquals("worker-2", parser.parse(result.threadBlocks().getFirst()).name());
    }

    @Test
    void rejectsDumpExceedingMaxThreads() {
        ThreadDumpLexer limited = new ThreadDumpLexer(2);
        String dump = threadBlock("t1", "0x1") + threadBlock("t2", "0x2") + threadBlock("t3", "0x3");
        assertThrows(DumpTooLargeException.class, () -> limited.tokenize(dump));
    }

    @Test
    void skipsOverlongLinesWithoutFailing() throws IOException {
        String dump = threadBlock("normal", "0x1") + "\"" + "x".repeat(20_000) + "\n";
        var result = lexer.tokenize(dump);
        assertEquals(1, result.threadBlocks().size());
    }

    @Test
    void semanticParserExtractsStateAndLocks() throws IOException {
        String dump = """
            "worker" #7 daemon prio=5 os_prio=0 tid=0x2 nid=0x1b0 waiting for monitor entry [0x00007f0000000000]
               java.lang.Thread.State: BLOCKED (on object monitor)
            \tat com.example.OrderService.<init>(OrderService.java:20)
            \t- waiting to lock <0x000000076ab220f8> (a java.lang.Object)
            \tat com.example.TaskRunner.lambda$run$0(TaskRunner.java:33)

               Locked ownable synchronizers:
            \t- None
            """;
        var result = lexer.tokenize(dump);
        assertEquals(1, result.threadBlocks().size());

        var info = parser.parse(result.threadBlocks().getFirst());
        assertEquals("worker", info.name());
        assertTrue(info.daemon());
        assertEquals(ThreadState.BLOCKED, info.state());
        assertEquals(List.of("<init>", "lambda$run$0"),
            info.stackTrace().stream().map(f -> f.methodName()).toList());
        assertEquals("0x000000076ab220f8", info.blockingLockAddress());
    }

    @Test
    void waitingOnConditionWithoutStateLineIsNotTimedWaiting() throws IOException {
        String dump = "\"parked\" #9 prio=5 os_prio=0 tid=0x3 nid=0x1b1 waiting on condition [0x00007f0000000000]\n" +
                      "\tat jdk.internal.misc.Unsafe.park(Native Method)\n";
        var result = lexer.tokenize(dump);
        var info = parser.parse(result.threadBlocks().getFirst());
        assertEquals(ThreadState.WAITING, info.state(),
            "无 Thread.State 行时 'waiting on condition' 不应武断标为 TIMED_WAITING");
    }
}
