package com.threadscope.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 相似堆栈聚合组 — 将堆栈完全相同的线程合并展示。
 *
 * v3: 新增 groupLabel — 生成人类可读的调用链摘要标签，
 *     解决多个组 meaningfulTopFrame 相同但调用链不同时无法区分的问题。
 *
 *     例如:
 *     - "LinkedBlockingQueue.take ← Tomcat ThreadPool"
 *     - "LinkedBlockingQueue.take ← Dubbo InternalRunnable"
 *     - "LinkedBlockingQueue.take ← JDK ThreadPoolExecutor"
 */
public record StackAggregateGroup(
    String fingerprint,
    int threadCount,
    List<StackFrame> representativeStack,
    List<String> threadNames,
    Map<ThreadState, Integer> stateDistribution
) {
    /**
     * 主要状态 — 组内占比最高的线程状态
     */
    @JsonProperty("dominantState")
    public ThreadState dominantState() {
        return stateDistribution.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(ThreadState.UNKNOWN);
    }

    /**
     * 获取第一个有意义的帧 (跳过 Unsafe.park / LockSupport.park 等 idle 帧)。
     * 由 Jackson 序列化时自动调用 (作为 JSON 属性 "meaningfulTopFrame")。
     */
    @JsonProperty("meaningfulTopFrame")
    public StackFrame meaningfulTopFrame() {
        if (representativeStack == null || representativeStack.isEmpty()) return null;
        for (StackFrame frame : representativeStack) {
            if (!isIdleFrame(frame.fullMethod())) {
                return frame;
            }
        }
        return representativeStack.getFirst();
    }

    /**
     * 生成人类可读的调用链摘要标签。
     *
     * 策略：从 meaningfulTopFrame 开始，向调用方（栈的更深处）寻找
     * 第一个"有辨识度"的帧，组合成 "TopFrame ← CallerContext" 格式。
     *
     * 例如:
     *   representativeStack = [Unsafe.park, LockSupport.park, AQS.await,
     *                          LinkedBlockingQueue.take,
     *                          ThreadPoolExecutor.getTask,
     *                          ThreadPoolExecutor.runWorker,
     *                          ThreadPoolExecutor$Worker.run,
     *                          Thread.run]
     *
     *   → meaningfulTopFrame = LinkedBlockingQueue.take   (index 3)
     *   → callerFrame = ThreadPoolExecutor.getTask        (index 4, 不同类名)
     *   → groupLabel = "LinkedBlockingQueue.take ← ThreadPoolExecutor.getTask"
     *
     * 如果调用者和 meaningfulTop 类名相同，则继续向下找不同类名的帧。
     * 如果找不到有辨识度的调用者，只返回 meaningfulTopFrame 的短名。
     *
     * Jackson 自动序列化为 "groupLabel" JSON 字段。
     */
    @JsonProperty("groupLabel")
    public String groupLabel() {
        if (representativeStack == null || representativeStack.isEmpty()) return "—";

        // 1. 找到 meaningfulTopFrame 的索引
        int topIdx = 0;
        for (int i = 0; i < representativeStack.size(); i++) {
            if (!isIdleFrame(representativeStack.get(i).fullMethod())) {
                topIdx = i;
                break;
            }
        }

        StackFrame topFrame = representativeStack.get(topIdx);
        String topShort = shortName(topFrame);
        String topClassName = topFrame.className();

        // 2. 寻找两层调用者上下文:
        //    - firstCaller: 第一个不同类名的非 idle 非 generic 帧
        //    - frameworkCaller: 第一个非 JDK 框架帧 (org.apache.*, com.*, io.* 等)
        StackFrame firstCaller = null;
        StackFrame frameworkCaller = null;

        for (int i = topIdx + 1; i < representativeStack.size(); i++) {
            StackFrame caller = representativeStack.get(i);
            String callerFull = caller.fullMethod();

            if (isIdleFrame(callerFull)) continue;
            if (isGenericRunFrame(callerFull)) continue;
            if (caller.className().equals(topClassName)) continue;

            // 记录第一个不同类名的调用者
            if (firstCaller == null) {
                firstCaller = caller;
            }

            // 继续寻找第一个非 JDK 框架帧
            if (!caller.isJdkFrame() && frameworkCaller == null) {
                frameworkCaller = caller;
                break; // 找到非 JDK 帧就停
            }
        }

        // 3. 组合标签
        if (frameworkCaller != null && frameworkCaller != firstCaller) {
            // 有非 JDK 框架帧且不是 firstCaller → 展示完整上下文
            // e.g. "LinkedBlockingQueue.take ← InternalRunnable.run"
            return topShort + " ← " + shortName(frameworkCaller);
        }
        if (frameworkCaller != null) {
            // 非 JDK 帧就是 firstCaller
            return topShort + " ← " + shortName(frameworkCaller);
        }
        if (firstCaller != null) {
            // 只有 JDK 帧做 caller，尝试用线程名做补充
            String callerLabel = topShort + " ← " + shortName(firstCaller);
            if (!threadNames.isEmpty()) {
                String poolHint = extractPoolHint(threadNames.getFirst());
                if (poolHint != null) {
                    return callerLabel + " (" + poolHint + ")";
                }
            }
            return callerLabel;
        }

        // 4. 都找不到 → 用线程名提示
        if (!threadNames.isEmpty()) {
            String poolHint = extractPoolHint(threadNames.getFirst());
            if (poolHint != null) {
                return topShort + " (" + poolHint + ")";
            }
        }

        return topShort;
    }

    // ── 工具方法 ──

    private static String shortName(StackFrame frame) {
        String cls = frame.className();
        int lastDot = cls.lastIndexOf('.');
        String simple = lastDot >= 0 ? cls.substring(lastDot + 1) : cls;
        return simple + "." + frame.methodName();
    }

    private static boolean isIdleFrame(String fullMethod) {
        return fullMethod.startsWith("jdk.internal.misc.Unsafe.park")
            || fullMethod.startsWith("java.lang.Object.wait")
            || fullMethod.startsWith("java.lang.Thread.sleep")
            || fullMethod.startsWith("java.lang.Thread.yield")
            || fullMethod.startsWith("sun.misc.Unsafe.park")
            || fullMethod.startsWith("java.util.concurrent.locks.LockSupport.park")
            || fullMethod.startsWith("java.util.concurrent.locks.LockSupport.parkNanos")
            || fullMethod.startsWith("java.util.concurrent.locks.LockSupport.parkUntil")
            || fullMethod.startsWith("java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.await")
            || fullMethod.startsWith("java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionNode.block")
            || fullMethod.startsWith("java.util.concurrent.ForkJoinPool.unmanagedBlock")
            || fullMethod.startsWith("java.util.concurrent.ForkJoinPool.managedBlock")
            || fullMethod.startsWith("sun.nio.ch.EPollSelectorImpl.doSelect")
            || fullMethod.startsWith("sun.nio.ch.SelectorImpl.");
    }

    /**
     * 通用线程运行框架帧 — 几乎所有线程都以这些帧结尾，不提供辨识度。
     */
    private static final Set<String> GENERIC_RUN_FRAMES = Set.of(
        "java.lang.Thread.run",
        "java.lang.Thread.runWith",
        "java.util.concurrent.ThreadPoolExecutor$Worker.run",
        "java.util.concurrent.ForkJoinWorkerThread.run",
        "java.util.concurrent.ForkJoinPool.runWorker"
    );

    private static boolean isGenericRunFrame(String fullMethod) {
        return GENERIC_RUN_FRAMES.contains(fullMethod);
    }

    /**
     * 从线程名中提取池提示。
     * 例如 "http-nio-8080-exec-1" → "Tomcat",
     *      "DubboServerHandler-10.0.0.1:20880-thread-5" → "Dubbo"
     */
    private static String extractPoolHint(String threadName) {
        if (threadName == null) return null;
        String lower = threadName.toLowerCase();
        if (lower.startsWith("http-nio-") || lower.startsWith("http-apr-") || lower.contains("tomcat")) return "Tomcat";
        if (lower.startsWith("dubbo")) return "Dubbo";
        if (lower.startsWith("pinpoint")) return "Pinpoint";
        if (lower.startsWith("nettyclient") || lower.startsWith("nettyserver") || lower.contains("nioeventloop")) return "Netty";
        if (lower.startsWith("hikari")) return "HikariCP";
        if (lower.startsWith("kafka")) return "Kafka";
        if (lower.startsWith("grpc-")) return "gRPC";
        if (lower.startsWith("lettuce-") || lower.contains("redis")) return "Redis";
        if (lower.startsWith("rocketmq")) return "RocketMQ";
        if (lower.startsWith("pool-")) return "ThreadPool";
        return null;
    }
}
