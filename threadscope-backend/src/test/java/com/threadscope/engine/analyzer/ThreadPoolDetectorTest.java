package com.threadscope.engine.analyzer;

import com.threadscope.model.ThreadPoolInfo;
import com.threadscope.model.ThreadState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static com.threadscope.engine.analyzer.TestThreads.thread;
import static org.junit.jupiter.api.Assertions.*;

class ThreadPoolDetectorTest {

    private final ThreadPoolDetector detector = new ThreadPoolDetector();

    private static final String[] TOMCAT_IDLE = {
        "jdk.internal.misc.Unsafe.park",
        "java.util.concurrent.locks.LockSupport.parkNanos",
        "java.util.concurrent.LinkedBlockingQueue.poll",
        "org.apache.tomcat.util.threads.TaskQueue.poll",
        "org.apache.tomcat.util.threads.ThreadPoolExecutor.getTask",
        "org.apache.tomcat.util.threads.ThreadPoolExecutor.runWorker",
        "java.lang.Thread.run"
    };

    private static final String[] TOMCAT_WAITING_DB = {
        "jdk.internal.misc.Unsafe.park",
        "java.util.concurrent.locks.LockSupport.parkNanos",
        "java.util.concurrent.SynchronousQueue$TransferStack.transfer",
        "java.util.concurrent.SynchronousQueue.poll",
        "com.zaxxer.hikari.util.ConcurrentBag.borrow",
        "com.zaxxer.hikari.pool.HikariPool.getConnection",
        "com.acme.order.OrderDao.find",
        "org.apache.tomcat.util.threads.ThreadPoolExecutor.runWorker",
        "java.lang.Thread.run"
    };

    private ThreadPoolInfo pool(List<ThreadPoolInfo> pools, String name) {
        return pools.stream().filter(p -> p.poolName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void waitingForDbConnectionCountsAsBusy() {
        var threads = IntStream.range(0, 10)
            .mapToObj(i -> thread("http-nio-8080-exec-" + i, ThreadState.TIMED_WAITING,
                i < 9 ? TOMCAT_WAITING_DB : TOMCAT_IDLE))
            .toList();

        ThreadPoolInfo tomcat = pool(detector.detect(threads), "http-nio-8080-exec");
        assertEquals("Tomcat", tomcat.poolType());
        assertEquals(9, tomcat.busyCount(), "等连接池的 WAITING 线程占着池容量，必须算忙碌");
        assertEquals(1, tomcat.idleCount());
        assertEquals(90.0, tomcat.utilizationRate(), 0.01);
    }

    @Test
    void poolExhaustionReportedWhenWorkersWaitOnResources() {
        var threads = IntStream.range(0, 10)
            .mapToObj(i -> thread("http-nio-8080-exec-" + i, ThreadState.TIMED_WAITING, TOMCAT_WAITING_DB))
            .toList();
        var pools = detector.detect(threads);
        var report = new HealthChecker().check(threads, null, List.of(), pools);
        assertTrue(report.risks().stream().anyMatch(r -> r.category().equals("POOL_EXHAUSTION")));
    }

    @Test
    void customLoopWaitingOnQueueIsIdle() {
        var threads = IntStream.range(0, 3)
            .mapToObj(i -> thread("order-sync-" + i, ThreadState.WAITING,
                "jdk.internal.misc.Unsafe.park",
                "java.util.concurrent.locks.LockSupport.park",
                "java.util.concurrent.LinkedBlockingQueue.take",
                "com.acme.order.SyncWorker.run",
                "java.lang.Thread.run"))
            .toList();
        ThreadPoolInfo p = pool(detector.detect(threads), "order-sync");
        assertEquals(3, p.idleCount());
    }

    @Test
    void recognizesFrameworkPools() {
        var threads = List.of(
            thread("tomcat-exec-1", ThreadState.RUNNABLE, "com.acme.A.b"),
            thread("tomcat-exec-2", ThreadState.RUNNABLE, "com.acme.A.b"),
            thread("XNIO-1 task-1", ThreadState.RUNNABLE, "com.acme.A.b"),
            thread("XNIO-1 task-2", ThreadState.RUNNABLE, "com.acme.A.b"),
            thread("vert.x-eventloop-thread-0", ThreadState.RUNNABLE, "sun.nio.ch.EPoll.wait"),
            thread("vert.x-eventloop-thread-1", ThreadState.RUNNABLE, "sun.nio.ch.EPoll.wait"),
            thread("task-1", ThreadState.RUNNABLE, "com.acme.A.b"),
            thread("task-2", ThreadState.RUNNABLE, "com.acme.A.b"),
            thread("DefaultQuartzScheduler_Worker-1", ThreadState.RUNNABLE, "com.acme.A.b"),
            thread("DefaultQuartzScheduler_Worker-2", ThreadState.RUNNABLE, "com.acme.A.b"),
            thread("xxl-job, executor JobThread-12-1663219939000", ThreadState.RUNNABLE, "com.acme.A.b"),
            thread("xxl-job, executor JobThread-13-1663219939001", ThreadState.RUNNABLE, "com.acme.A.b"),
            thread("lettuce-nioEventLoop-4-1", ThreadState.RUNNABLE, "sun.nio.ch.EPoll.wait"),
            thread("lettuce-nioEventLoop-4-2", ThreadState.RUNNABLE, "sun.nio.ch.EPoll.wait"),
            thread("hystrix-OrderGroup-1", ThreadState.RUNNABLE, "com.acme.A.b"),
            thread("hystrix-OrderGroup-2", ThreadState.RUNNABLE, "com.acme.A.b"),
            thread("AMQP Connection 10.0.0.1:5672", ThreadState.RUNNABLE, "com.acme.A.b"),
            thread("AMQP Connection 10.0.0.2:5672", ThreadState.RUNNABLE, "com.acme.A.b")
        );
        var pools = detector.detect(threads);
        assertEquals("Tomcat", pool(pools, "tomcat-exec").poolType());
        assertEquals(ThreadPoolDetector.CATEGORY_WEB, pool(pools, "tomcat-exec").category());
        assertEquals("Undertow", pool(pools, "XNIO-1 task").poolType());
        assertEquals("Vert.x-EventLoop", pool(pools, "vert.x-eventloop-thread").poolType());
        assertEquals(2, pool(pools, "vert.x-eventloop-thread").idleCount(), "停在 epoll 的事件循环是空闲");
        assertEquals("Spring-@Async", pool(pools, "task").poolType());
        assertFalse(pool(pools, "task").hints().isEmpty());
        assertEquals("Quartz", pool(pools, "DefaultQuartzScheduler_Worker").poolType());
        assertEquals("XXL-Job-JobThread", pool(pools, "xxl-job, executor JobThread").poolType());
        assertEquals("Lettuce", pool(pools, "lettuce-nioEventLoop").poolType());
        assertEquals("Hystrix", pool(pools, "hystrix-OrderGroup").poolType());
        assertEquals("RabbitMQ-Connection", pool(pools, "AMQP Connection").poolType());
    }

    @Test
    void anonymousPoolGetsNamingAdvice() {
        var threads = List.of(
            thread("pool-3-thread-1", ThreadState.RUNNABLE, "com.acme.A.b"),
            thread("pool-3-thread-2", ThreadState.RUNNABLE, "com.acme.A.b"));
        var pools = detector.detect(threads);
        ThreadPoolInfo p = pool(pools, "pool-3-thread");
        assertEquals(ThreadPoolDetector.CATEGORY_ANONYMOUS, p.category());
        assertTrue(p.hints().getFirst().contains("未命名线程池"));

        var report = new HealthChecker().check(threads, null, List.of(), pools);
        assertTrue(report.risks().stream().anyMatch(r -> r.category().equals("ANONYMOUS_THREAD_POOL")));
    }

    @Test
    void duplicateNamesAreKeptAsSeparateThreads() {
        var a = TestThreads.thread("worker", "#10", ThreadState.RUNNABLE, null, null, List.of(),
            com.threadscope.model.ThreadKind.PLATFORM, "com.acme.A.b");
        var b = TestThreads.thread("worker", "#11", ThreadState.RUNNABLE, null, null, List.of(),
            com.threadscope.model.ThreadKind.PLATFORM, "com.acme.A.b");
        var c = TestThreads.thread("worker-1", "#12", ThreadState.RUNNABLE, null, null, List.of(),
            com.threadscope.model.ThreadKind.PLATFORM, "com.acme.A.b");
        var d = TestThreads.thread("worker-2", "#13", ThreadState.RUNNABLE, null, null, List.of(),
            com.threadscope.model.ThreadKind.PLATFORM, "com.acme.A.b");
        var pools = detector.detect(List.of(a, b, c, d));
        assertEquals(List.of("#12", "#13"), pool(pools, "worker").threadKeys());
    }
}
