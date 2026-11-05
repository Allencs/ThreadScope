package com.threadscope.engine.analyzer;

import com.threadscope.model.HealthReport;
import com.threadscope.model.ThreadState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static com.threadscope.engine.analyzer.TestThreads.thread;
import static org.junit.jupiter.api.Assertions.*;

class HealthCheckerCpuTest {

    private final HealthChecker checker = new HealthChecker();

    private HealthReport check(List<com.threadscope.model.ThreadInfo> threads) {
        return checker.check(threads, null, List.of(), List.of());
    }

    @Test
    void jdkTopFrameCpuHotspotIsReported() {
        var threads = IntStream.range(0, 3)
            .mapToObj(i -> thread("http-" + i, ThreadState.RUNNABLE,
                "java.util.regex.Pattern$Curly.match",
                "java.util.regex.Pattern$GroupTail.match",
                "java.util.regex.Matcher.matches",
                "com.acme.Validator.check"))
            .toList();

        var risks = check(threads).risks();
        var cpu = risks.stream().filter(r -> r.category().equals("CPU_HOTSPOT")).findFirst();
        assertTrue(cpu.isPresent(), "栈顶在 JDK 正则内部的 RUNNABLE 线程也是 CPU 热点");
        assertTrue(cpu.get().description().contains("com.acme.Validator.check"), "附带业务调用点");
        assertTrue(risks.stream().anyMatch(r -> r.category().equals("REGEX_BACKTRACKING")));
    }

    @Test
    void ioAndSelectorThreadsAreNotCpuHotspots() {
        var io = IntStream.range(0, 5)
            .mapToObj(i -> thread("io-" + i, ThreadState.RUNNABLE,
                "java.net.SocketInputStream.socketRead0", "java.net.SocketInputStream.read", "com.acme.Client.call"));
        var loops = IntStream.range(0, 5)
            .mapToObj(i -> thread("loop-" + i, ThreadState.RUNNABLE,
                "sun.nio.ch.EPoll.wait", "sun.nio.ch.EPollSelectorImpl.doSelect", "io.netty.channel.nio.NioEventLoop.run"));
        var risks = check(java.util.stream.Stream.concat(io, loops).toList()).risks();
        assertTrue(risks.stream().noneMatch(r -> r.category().equals("CPU_HOTSPOT")));
    }

    @Test
    void exceptionStormIsDetected() {
        var threads = IntStream.range(0, 4)
            .mapToObj(i -> thread("t-" + i, ThreadState.RUNNABLE,
                "java.lang.Throwable.fillInStackTrace",
                "java.lang.Throwable.fillInStackTrace",
                "java.lang.Throwable.<init>",
                "com.acme.Parser.parse"))
            .toList();
        var risks = check(threads).risks();
        assertTrue(risks.stream().anyMatch(r -> r.category().equals("EXCEPTION_STORM")));
        assertTrue(risks.stream().anyMatch(r -> r.category().equals("CPU_HOTSPOT")),
            "fillInStackTrace 是代表计算的 native 栈顶");
    }

    @Test
    void thresholdsAreConfigurable() {
        var strict = new HealthChecker(new com.threadscope.config.HealthThresholds(
            0.05, 0.15, 5, 0.9, 5, 2, 3, 200, 500, 10, 5, 20, 60, 50, 3));
        var threads = List.of(
            thread("a", ThreadState.WAITING, "java.lang.Object.wait"),
            thread("b", ThreadState.WAITING, "java.lang.Object.wait"));
        var report = strict.check(threads, null, List.of(), List.of());
        assertTrue(report.risks().stream().anyMatch(r -> r.category().equals("THREAD_COUNT")));
    }
}
