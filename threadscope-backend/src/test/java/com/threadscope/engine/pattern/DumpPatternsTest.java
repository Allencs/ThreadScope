package com.threadscope.engine.pattern;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.*;

class DumpPatternsTest {

    // ━━━ THREAD_HEADER ━━━

    @Test
    void classicHeaderCapturesStateDescAndStackAddress() {
        Matcher m = DumpPatterns.THREAD_HEADER.matcher(
            "\"http-nio-8080-exec-1\" #42 daemon prio=5 os_prio=0 cpu=125.40ms elapsed=3847.12s " +
            "tid=0x00007f8a3c01e800 nid=0x1a3 runnable [0x00007f89e4ffd000]");
        assertTrue(m.matches());
        assertEquals("http-nio-8080-exec-1", m.group(1));
        assertEquals("42", m.group(2));
        assertNotNull(m.group(4), "daemon flag");
        assertEquals("0x1a3", m.group(10));
        assertEquals("runnable", m.group(11), "状态描述不应包含栈地址");
        assertEquals("0x00007f89e4ffd000", m.group(12), "栈地址必须被单独捕获");
    }

    @Test
    void jdk24HeaderWithBracketNidAndMultiWordDesc() {
        Matcher m = DumpPatterns.THREAD_HEADER.matcher(
            "\"Reference Handler\" #20 [62] daemon prio=10 os_prio=0 cpu=3408.09ms elapsed=363507.64s " +
            "tid=0x00007f8537cf3800 nid=62 waiting on condition [0x00007f8112bfc000]");
        assertTrue(m.matches());
        assertEquals("62", m.group(3), "JDK 24+ 方括号 nid");
        assertEquals("waiting on condition", m.group(11));
        assertEquals("0x00007f8112bfc000", m.group(12));
    }

    @Test
    void headerWithoutStackAddress() {
        Matcher m = DumpPatterns.THREAD_HEADER.matcher(
            "\"GC Thread#0\" #3 prio=9 os_prio=0 tid=0x00007f8a3c05e000 nid=0x19f runnable");
        assertTrue(m.matches());
        assertEquals("GC Thread#0", m.group(1));
        assertEquals("runnable", m.group(11));
        assertNull(m.group(12));
    }

    @Test
    void simpleHeaderMatchesAndResistsBacktracking() {
        Matcher m = DumpPatterns.THREAD_HEADER_SIMPLE.matcher(
            "\"Thread-1\" daemon prio=5 tid=0x1 nid=0x2b waiting");
        assertTrue(m.find());
        assertEquals("Thread-1", m.group(1));
        assertEquals("0x2b", m.group(2));
        assertEquals("waiting", m.group(3));

        // 恶意构造行必须在线性时间内完成 (无灾难性回溯)
        String evil = "\"" + "a\" ".repeat(3000) + "x".repeat(2000);
        long start = System.nanoTime();
        DumpPatterns.THREAD_HEADER_SIMPLE.matcher(evil).find();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 1000, "疑似正则回溯：耗时 " + elapsedMs + "ms");
    }

    // ━━━ STACK_FRAME ━━━

    @Test
    void stackFrameMatchesRegularMethod() {
        Matcher m = DumpPatterns.STACK_FRAME.matcher(
            "\tat com.example.service.OrderService.processOrder(OrderService.java:87)");
        assertTrue(m.find());
        assertEquals("com.example.service.OrderService", m.group(1));
        assertEquals("processOrder", m.group(2));
        assertEquals("OrderService.java:87", m.group(3));
    }

    @Test
    void stackFrameMatchesConstructorAndStaticInitializer() {
        Matcher init = DumpPatterns.STACK_FRAME.matcher(
            "\tat com.example.Foo.<init>(Foo.java:10)");
        assertTrue(init.find(), "<init> 构造器帧必须被解析");
        assertEquals("com.example.Foo", init.group(1));
        assertEquals("<init>", init.group(2));

        Matcher clinit = DumpPatterns.STACK_FRAME.matcher(
            "\tat com.example.Foo.<clinit>(Foo.java:5)");
        assertTrue(clinit.find(), "<clinit> 静态初始化帧必须被解析");
        assertEquals("<clinit>", clinit.group(2));
    }

    @Test
    void stackFrameMatchesSyntheticMethods() {
        Matcher lambda = DumpPatterns.STACK_FRAME.matcher(
            "\tat com.example.TaskRunner.lambda$run$0(TaskRunner.java:33)");
        assertTrue(lambda.find(), "lambda 合成方法帧必须被解析");
        assertEquals("com.example.TaskRunner", lambda.group(1));
        assertEquals("lambda$run$0", lambda.group(2));

        Matcher access = DumpPatterns.STACK_FRAME.matcher(
            "\tat com.example.Outer.access$100(Outer.java:12)");
        assertTrue(access.find(), "access$ 桥接方法帧必须被解析");
        assertEquals("access$100", access.group(2));
    }

    @Test
    void stackFrameMatchesInnerClassAndModulePrefix() {
        Matcher inner = DumpPatterns.STACK_FRAME.matcher(
            "\tat java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:637)");
        assertTrue(inner.find());
        assertEquals("java.util.concurrent.ThreadPoolExecutor$Worker", inner.group(1));
        assertEquals("run", inner.group(2));

        Matcher module = DumpPatterns.STACK_FRAME.matcher(
            "\tat java.base@21/java.lang.Thread.run(Thread.java:833)");
        assertTrue(module.find(), "带模块前缀的帧必须被解析");
        assertEquals("run", module.group(2));
    }
}
