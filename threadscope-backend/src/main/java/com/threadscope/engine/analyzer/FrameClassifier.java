package com.threadscope.engine.analyzer;

import com.threadscope.model.StackFrame;
import com.threadscope.model.ThreadInfo;
import com.threadscope.model.ThreadState;

import java.util.List;
import java.util.Set;

/**
 * 栈帧语义分类器。
 *
 * 核心用途：
 * 1. 区分 "CPU 型 RUNNABLE" 和 "IO 型 RUNNABLE"。JVM 把阻塞在 native socket read / epoll 上的线程
 *    也标为 RUNNABLE，这是 dump 分析最经典的误判点。
 * 2. 判断池内工作线程是否真正空闲。WAITING 不等于空闲：Tomcat 线程卡在 HikariPool.getConnection
 *    同样是 WAITING，但它正占着池容量 — 只能看栈判断它在等任务还是在等资源。
 */
public final class FrameClassifier {

    private FrameClassifier() {}

    /** 网络/磁盘 IO 的 native 阻塞点 — 栈顶命中即认为线程在等 IO */
    private static final Set<String> IO_METHODS = Set.of(
        // 经典 BIO socket
        "java.net.SocketInputStream.socketRead0",
        "java.net.SocketInputStream.read",
        "java.net.SocketOutputStream.socketWrite0",
        "java.net.PlainSocketImpl.socketAccept",
        "java.net.ServerSocket.accept",
        // NIO — 注意：不含 EPoll.wait / KQueue.poll 等 Selector 轮询方法，
        // 它们是事件循环 (Netty/HTTP reactor) 的正常空闲态，不代表在等下游 IO。
        // Net.poll 则是 JDK 13+ NioSocketImpl 阻塞读写的真实标志，保留。
        "sun.nio.ch.Net.poll",
        "sun.nio.ch.Net.accept",
        "sun.nio.ch.SocketDispatcher.read0",
        "sun.nio.ch.SocketDispatcher.write0",
        "sun.nio.ch.SocketChannelImpl.read",
        "sun.nio.ch.ServerSocketChannelImpl.accept",
        "sun.nio.ch.DatagramChannelImpl.receive0",
        // 文件 IO
        "sun.nio.ch.FileDispatcherImpl.read0",
        "sun.nio.ch.FileDispatcherImpl.write0",
        "sun.nio.ch.FileDispatcherImpl.force0",
        "java.io.FileInputStream.readBytes",
        "java.io.FileOutputStream.writeBytes",
        "java.io.RandomAccessFile.readBytes",
        "java.io.RandomAccessFile.writeBytes",
        // DNS
        "java.net.Inet4AddressImpl.lookupAllHostAddr",
        "java.net.Inet6AddressImpl.lookupAllHostAddr",
        "java.net.InetAddress$PlatformNameService.lookupAllHostAddr"
    );

    /** SSL/TLS 包装层 — 本身不是 native IO，但紧贴 IO 帧之上，参与近栈顶判定 */
    private static final Set<String> IO_WRAPPER_PREFIXES = Set.of(
        "sun.security.ssl.",
        "javax.net.ssl."
    );

    /**
     * 空闲等待型栈顶 — 线程停在这里属于常态 (等任务 / 等事件 / 等连接 / 睡眠)，
     * 多 dump 中栈不动也不算"卡死"，RUNNABLE 时也不算 CPU 热点。
     */
    private static final Set<String> IDLE_TOP_METHODS = Set.of(
        "jdk.internal.misc.Unsafe.park",
        "sun.misc.Unsafe.park",
        "java.lang.Object.wait",
        "java.lang.Object.wait0",
        "java.lang.Thread.sleep",
        "java.lang.Thread.sleep0",
        "java.lang.Thread.sleepNanos0",
        "java.lang.VirtualThread.takeVirtualThreadListToUnblock",
        // accept 循环 — 等新连接是常态
        "sun.nio.ch.Net.accept",
        "sun.nio.ch.ServerSocketChannelImpl.accept",
        "java.net.PlainSocketImpl.socketAccept",
        "java.net.DualStackPlainSocketImpl.accept0",
        // Selector / 事件循环轮询
        "sun.nio.ch.EPollArrayWrapper.epollWait",
        "sun.nio.ch.EPoll.wait",
        "sun.nio.ch.KQueue.poll",
        "sun.nio.ch.KQueue.keventPoll",
        "sun.nio.ch.KQueueArrayWrapper.kevent0",
        "sun.nio.ch.WindowsSelectorImpl$SubSelector.poll0",
        "sun.nio.ch.Iocp.getQueuedCompletionStatus",
        "sun.nio.fs.LinuxWatchService.poll",
        "java.lang.ref.Reference.waitForReferencePendingList",
        "java.lang.ProcessHandleImpl.waitForProcessExit0",
        "java.lang.UNIXProcess.waitForProcessExit",
        "java.lang.ProcessImpl.waitFor",
        "jdk.internal.misc.Signal.waitForSignal",
        // Netty 事件循环 (epoll / kqueue / io_uring) — 等事件是常态
        "io.netty.channel.epoll.Native.epollWait",
        "io.netty.channel.epoll.Native.epollWait0",
        "io.netty.channel.epoll.Native.epollBusyWait0",
        "io.netty.channel.kqueue.Native.keventWait",
        "io.netty.channel.kqueue.Native.keventWait0",
        "io.netty.channel.uring.Native.ioUringEnter",
        "io.netty.incubator.channel.uring.Native.ioUringEnter",
        "org.apache.tomcat.jni.Poll.poll"
    );

    /**
     * 本身是 native、但代表 CPU 消耗的栈顶 — 其余 native 栈顶多为阻塞等待，不计入 CPU 热点。
     * Throwable.fillInStackTrace 扎堆是"异常风暴"的典型信号。
     */
    private static final Set<String> CPU_NATIVE_PREFIXES = Set.of(
        "java.util.zip.Inflater.inflate",
        "java.util.zip.Deflater.deflate",
        "java.util.zip.CRC32.update",
        "java.util.zip.Adler32.update",
        "java.lang.Throwable.fillInStackTrace",
        "java.lang.StackTraceElement.initStackTraceElements",
        "java.lang.System.arraycopy",
        "jdk.internal.misc.Unsafe.copyMemory0",
        "jdk.internal.misc.Unsafe.setMemory0",
        "java.lang.String.intern",
        "java.lang.Class.forName0",
        "java.lang.invoke.MethodHandleNatives.resolve"
    );

    /**
     * 等待中间层 — 位于"真正等待点"与"等待发起者"之间的同步原语帧。
     * 判定空闲时跳过它们，看第一个非中间层帧是谁在等。
     */
    private static final Set<String> WAIT_INTERMEDIATE_PREFIXES = Set.of(
        "jdk.internal.misc.Unsafe.park",
        "sun.misc.Unsafe.park",
        "java.util.concurrent.locks.LockSupport.",
        "java.util.concurrent.locks.AbstractQueuedSynchronizer",
        "java.util.concurrent.locks.AbstractQueuedLongSynchronizer",
        "java.util.concurrent.LinkedBlockingQueue.",
        "java.util.concurrent.LinkedBlockingDeque.",
        "java.util.concurrent.ArrayBlockingQueue.",
        "java.util.concurrent.SynchronousQueue",
        "java.util.concurrent.LinkedTransferQueue",
        "java.util.concurrent.DelayQueue.",
        "java.util.concurrent.PriorityBlockingQueue.",
        "java.util.concurrent.ForkJoinPool.managedBlock",
        "java.util.concurrent.ForkJoinPool.unmanagedBlock",
        "java.util.concurrent.ForkJoinPool.compensatedBlock",
        "java.util.concurrent.TimeUnit.sleep",
        "java.lang.Object.wait",
        "java.lang.Thread.sleep",
        "java.lang.ref.ReferenceQueue."
    );

    /**
     * 取任务方法 — 等待中间层之上第一个帧是它们，说明线程在等新任务 (空闲)。
     */
    private static final Set<String> TASK_FETCH_PREFIXES = Set.of(
        "java.util.concurrent.ThreadPoolExecutor.getTask",
        "java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue.take",
        "java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue.poll",
        "java.util.concurrent.ForkJoinPool.awaitWork",
        "java.util.concurrent.ForkJoinPool.runWorker",
        "org.apache.tomcat.util.threads.TaskQueue.",
        "org.apache.tomcat.util.threads.ThreadPoolExecutor.getTask",
        "org.eclipse.jetty.util.thread.QueuedThreadPool$Runner.idleJobPoll",
        "org.eclipse.jetty.util.thread.ReservedThreadExecutor$ReservedThread.reservedWait",
        "org.eclipse.jetty.util.BlockingArrayQueue.poll",
        "org.jboss.threads.EnhancedQueueExecutor$ThreadBody.run",
        "org.jboss.threads.EnhancedQueueExecutor$PoolThreadNode.park",
        "io.netty.util.concurrent.SingleThreadEventExecutor.takeTask",
        "io.netty.util.concurrent.GlobalEventExecutor.takeTask",
        "io.netty.util.concurrent.DefaultEventExecutor.run",
        "io.netty.util.HashedWheelTimer$Worker.waitForNextTick",
        "java.util.TimerThread.mainLoop",
        "org.quartz.simpl.SimpleThreadPool$WorkerThread.run",
        "org.quartz.core.QuartzSchedulerThread.run",
        "com.xxl.job.core.thread.JobThread.run",
        "java.lang.ref.Finalizer$FinalizerThread.run",
        "java.lang.ref.Reference$ReferenceHandler.run",
        "jdk.internal.ref.CleanerImpl.run",
        "jdk.internal.misc.InnocuousThread.run"
    );

    /** 线程启动引导帧 — 位于线程栈底部，不属于任何业务调用 */
    private static final Set<String> THREAD_BOOTSTRAP_PREFIXES = Set.of(
        "java.lang.Thread.run",
        "java.lang.Thread.runWith",
        "java.lang.VirtualThread.run",
        "jdk.internal.misc.InnocuousThread.run",
        "io.netty.util.concurrent.FastThreadLocalRunnable.run",
        "io.netty.util.internal.ThreadExecutorMap$2.run",
        "io.netty.util.concurrent.ThreadPerTaskExecutor"
    );

    /**
     * 判断单帧是否为 IO 阻塞点。
     * 类名可能带模块前缀 (java.base@21/java.net...)，先归一化。
     */
    public static boolean isIoFrame(StackFrame frame) {
        return IO_METHODS.contains(normalize(frame.fullMethod()));
    }

    /**
     * 判断 RUNNABLE 线程是否实际在等 IO。
     * 检查栈顶前 4 帧 — socketRead0 之上常有 SSL/BufferedStream 等薄包装。
     */
    public static boolean isIoBoundRunnable(ThreadInfo thread) {
        if (thread.state() != ThreadState.RUNNABLE || thread.stackTrace().isEmpty()) {
            return false;
        }
        int limit = Math.min(4, thread.stackTrace().size());
        for (int i = 0; i < limit; i++) {
            StackFrame frame = thread.stackTrace().get(i);
            String full = normalize(frame.fullMethod());
            if (IO_METHODS.contains(full)) return true;
            // 包装帧继续向下看，非包装帧则终止 (已进入业务逻辑)
            boolean isWrapper = IO_WRAPPER_PREFIXES.stream().anyMatch(full::startsWith);
            if (!isWrapper && i > 0) break;
        }
        return false;
    }

    /** 栈顶是否是空闲等待点 (park / wait / sleep / epoll / accept ...) */
    public static boolean isIdleTop(ThreadInfo thread) {
        if (thread.stackTrace() == null || thread.stackTrace().isEmpty()) return false;
        return IDLE_TOP_METHODS.contains(normalize(thread.topMethod()));
    }

    public static boolean isIdleMethod(String fullMethod) {
        return IDLE_TOP_METHODS.contains(normalize(fullMethod));
    }

    /**
     * 是否是 CPU 热点候选：RUNNABLE、有栈、栈顶既不是 IO 也不是空闲轮询；
     * native 栈顶只在明确代表计算的方法上才算 (inflate / fillInStackTrace 等)。
     */
    public static boolean isCpuCandidate(ThreadInfo thread) {
        if (thread.state() != ThreadState.RUNNABLE || thread.stackTrace().isEmpty()) return false;
        if (isIoBoundRunnable(thread) || isIdleTop(thread)) return false;
        StackFrame top = thread.stackTrace().getFirst();
        if (top.isNative()) {
            String full = normalize(top.fullMethod());
            return CPU_NATIVE_PREFIXES.stream().anyMatch(full::startsWith);
        }
        return true;
    }

    /**
     * 池内工作线程是否空闲 (可立即接收新任务)。
     *
     * - BLOCKED：在抢锁，一定忙
     * - RUNNABLE：只有停在 Selector/accept 轮询上 (事件循环、Acceptor) 才算空闲
     * - WAITING/TIMED_WAITING：跳过 park/队列/AQS 等中间层后，
     *     · 第一个帧是取任务方法 (getTask / TaskQueue.take / awaitWork ...) → 空闲
     *     · 或其下只剩线程引导帧 (自写的 while(true) queue.take() 主循环) → 空闲
     *     · 否则是业务代码在等资源 (连接池、Future、锁) → 忙
     */
    public static boolean isIdleWorker(ThreadInfo thread) {
        List<StackFrame> stack = thread.stackTrace();
        ThreadState state = thread.state();
        if (state == ThreadState.BLOCKED) return false;
        if (stack == null || stack.isEmpty()) {
            return state == ThreadState.WAITING || state == ThreadState.TIMED_WAITING;
        }
        if (state == ThreadState.RUNNABLE) {
            return isIdleTop(thread);
        }
        if (state != ThreadState.WAITING && state != ThreadState.TIMED_WAITING) {
            return false;
        }

        int i = 0;
        while (i < stack.size() && matchesAny(stack.get(i), WAIT_INTERMEDIATE_PREFIXES)) i++;
        if (i >= stack.size()) return true;

        if (matchesAny(stack.get(i), TASK_FETCH_PREFIXES)) return true;

        // 等待发起者之下只剩引导帧 → 线程主循环在等工作
        for (int j = i + 1; j < stack.size(); j++) {
            if (!matchesAny(stack.get(j), THREAD_BOOTSTRAP_PREFIXES)) return false;
        }
        return true;
    }

    /** 是否为线程引导帧 (Thread.run 等) */
    public static boolean isBootstrapFrame(StackFrame frame) {
        return matchesAny(frame, THREAD_BOOTSTRAP_PREFIXES);
    }

    private static boolean matchesAny(StackFrame frame, Set<String> prefixes) {
        String full = normalize(frame.fullMethod());
        for (String p : prefixes) {
            if (full.startsWith(p)) return true;
        }
        return false;
    }

    /** 去掉 JDK 9+ 的模块前缀: "java.base@21/java.net.Socket" → "java.net.Socket" */
    public static String normalize(String fullMethod) {
        int slash = fullMethod.indexOf('/');
        return slash >= 0 ? fullMethod.substring(slash + 1) : fullMethod;
    }
}
