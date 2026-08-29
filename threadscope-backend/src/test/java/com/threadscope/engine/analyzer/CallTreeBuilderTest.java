package com.threadscope.engine.analyzer;

import com.threadscope.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CallTreeBuilderTest {

    private final CallTreeBuilder builder = new CallTreeBuilder();

    private static ThreadInfo thread(String name, ThreadState state, String... framesTopFirst) {
        List<StackFrame> stack = java.util.Arrays.stream(framesTopFirst)
            .map(f -> {
                int dot = f.lastIndexOf('.');
                return new StackFrame(f.substring(0, dot), f.substring(dot + 1), "X.java:1", 1);
            })
            .toList();
        return new ThreadInfo(name, 1, false, 5, 0, null, null, "0x1", "0x1", 1,
            state, null, null, stack, List.of(), List.of());
    }

    @Test
    void mergesStacksBottomUp() {
        // 两个线程共享栈底 Thread.run → Service.handle，栈顶分叉
        var t1 = thread("t1", ThreadState.RUNNABLE,
            "com.example.Dao.query", "com.example.Service.handle", "java.lang.Thread.run");
        var t2 = thread("t2", ThreadState.RUNNABLE,
            "com.example.Cache.get", "com.example.Service.handle", "java.lang.Thread.run");

        CallTreeNode root = builder.build(List.of(t1, t2), null);

        assertEquals(2, root.value());
        assertEquals(1, root.children().size());
        CallTreeNode threadRun = root.children().getFirst();
        assertEquals("Thread.run", threadRun.name());
        assertEquals(2, threadRun.value());

        CallTreeNode handle = threadRun.children().getFirst();
        assertEquals("Service.handle", handle.name());
        assertEquals(2, handle.value());
        assertEquals(2, handle.children().size(), "栈顶分叉成两个叶子");
    }

    @Test
    void stateFilterLimitsThreads() {
        var runnable = thread("r", ThreadState.RUNNABLE, "a.B.c", "java.lang.Thread.run");
        var blocked = thread("b", ThreadState.BLOCKED, "a.B.c", "java.lang.Thread.run");

        CallTreeNode root = builder.build(List.of(runnable, blocked), ThreadState.BLOCKED);
        assertEquals(1, root.value());
    }

    @Test
    void emptyStacksAreSkipped() {
        var noStack = new ThreadInfo("empty", 1, false, 5, 0, null, null, "0x1", "0x1", 1,
            ThreadState.RUNNABLE, null, null, List.of(), List.of(), List.of());
        CallTreeNode root = builder.build(List.of(noStack), null);
        assertEquals(0, root.value());
        assertTrue(root.children().isEmpty());
    }
}
