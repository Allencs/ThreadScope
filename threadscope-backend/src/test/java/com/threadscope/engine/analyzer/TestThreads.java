package com.threadscope.engine.analyzer;

import com.threadscope.model.*;

import java.util.Arrays;
import java.util.List;

/** 测试用线程构造工具 */
final class TestThreads {

    private TestThreads() {}

    static List<StackFrame> frames(String... methods) {
        return Arrays.stream(methods)
            .map(f -> {
                int dot = f.lastIndexOf('.');
                return new StackFrame(f.substring(0, dot), f.substring(dot + 1), "X.java:1", 1);
            })
            .toList();
    }

    static ThreadInfo thread(String name, ThreadState state, String... methods) {
        return new ThreadInfo(name, 1, false, 5, 0, null, null, "0x1", "0x1", 1,
            state, null, null, frames(methods), List.of(), List.of());
    }

    static ThreadInfo thread(String name, String key, ThreadState state, String elapsed, String cpu,
                             List<LockAction> locks, ThreadKind kind, String... methods) {
        return new ThreadInfo(name, 0, false, 5, 0, cpu, elapsed, "0x1", "0x1", 1,
            state, null, null, frames(methods), locks, List.of(), key, kind, null, null, false);
    }
}
