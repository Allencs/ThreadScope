package com.threadscope.engine.analyzer;

import com.threadscope.model.StackFrame;
import com.threadscope.model.ThreadInfo;
import com.threadscope.model.ThreadState;

import java.util.Set;

/**
 * 栈帧语义分类器。
 *
 * 核心用途：区分 "CPU 型 RUNNABLE" 和 "IO 型 RUNNABLE"。
 * JVM 把阻塞在 native socket read / epoll 上的线程也标为 RUNNABLE，
 * 这是 dump 分析最经典的误判点 — 大量线程"看起来在跑"，实际在等下游返回。
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

    /** 去掉 JDK 9+ 的模块前缀: "java.base@21/java.net.Socket" → "java.net.Socket" */
    private static String normalize(String fullMethod) {
        int slash = fullMethod.indexOf('/');
        return slash >= 0 ? fullMethod.substring(slash + 1) : fullMethod;
    }
}
