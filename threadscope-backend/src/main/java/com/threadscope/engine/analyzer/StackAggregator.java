package com.threadscope.engine.analyzer;

import com.threadscope.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 相似堆栈聚合器。
 *
 * 核心策略: 基于堆栈指纹 (fingerprint) 进行精确分组。
 * 堆栈完全相同的线程会被合并，极大减少视觉噪音。
 *
 * v2: 使用智能指纹 — 跳过通用的 JDK idle/parking/waiting 帧，
 *     从第一个有意义的帧开始生成指纹，使分组结果更有价值。
 *
 * 典型场景: 200个Tomcat线程都在等待同一个DB连接 → 显示为1个聚合组[×200]
 */
public class StackAggregator {

    /**
     * 常见的 JDK idle/parking/waiting 方法前缀 — 这些帧在聚合时应该被跳过。
     * 它们几乎出现在所有 WAITING/TIMED_WAITING 线程的栈顶，不提供有用的区分信息。
     */
    private static final Set<String> IDLE_METHODS = Set.of(
        "jdk.internal.misc.Unsafe.park",
        "java.lang.Object.wait",
        "java.lang.Object.wait0",
        "java.lang.Thread.sleep",
        "java.lang.Thread.sleep0",
        "java.lang.Thread.yield",
        "java.lang.Thread.yield0",
        "sun.misc.Unsafe.park",
        "java.util.concurrent.locks.LockSupport.park",
        "java.util.concurrent.locks.LockSupport.parkNanos",
        "java.util.concurrent.locks.LockSupport.parkUntil",
        "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.await",
        "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.awaitNanos",
        "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.awaitUntil",
        "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionNode.block",
        "java.util.concurrent.ForkJoinPool.unmanagedBlock",
        "java.util.concurrent.ForkJoinPool.managedBlock",
        "sun.nio.ch.EPollSelectorImpl.doSelect",
        "sun.nio.ch.SelectorImpl.lockAndDoSelect",
        "sun.nio.ch.SelectorImpl.select"
    );

    /**
     * 聚合相似堆栈的线程。
     *
     * @param threads      所有线程
     * @param minGroupSize 最小分组大小 (默认2，即至少2个线程相同才聚合)
     */
    public List<StackAggregateGroup> aggregate(List<ThreadInfo> threads, int minGroupSize) {
        return threads.stream()
            .filter(t -> !t.stackTrace().isEmpty())
            .collect(Collectors.groupingBy(this::smartFingerprint))
            .entrySet().stream()
            .filter(e -> e.getValue().size() >= minGroupSize)
            .map(e -> {
                String fingerprint = e.getKey();
                List<ThreadInfo> group = e.getValue();
                ThreadInfo representative = group.getFirst();

                Map<ThreadState, Integer> stateDist = group.stream()
                    .collect(Collectors.groupingBy(
                        ThreadInfo::state,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                    ));

                return new StackAggregateGroup(
                    fingerprint,
                    group.size(),
                    representative.stackTrace(),
                    group.stream().map(ThreadInfo::name).toList(),
                    stateDist
                );
            })
            .sorted(Comparator.comparingInt(StackAggregateGroup::threadCount).reversed())
            .toList();
    }

    /**
     * 智能指纹 — 跳过栈顶的通用 idle 帧，从第一个有意义的帧开始生成指纹。
     * 如果整个栈都是 idle 帧（不太可能），则回退到使用完整栈。
     */
    private String smartFingerprint(ThreadInfo thread) {
        List<StackFrame> stack = thread.stackTrace();
        if (stack == null || stack.isEmpty()) return "<empty>";

        int startIdx = findFirstMeaningfulIndex(stack);

        // 从 startIdx 开始生成指纹
        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i < stack.size(); i++) {
            if (sb.length() > 0) sb.append('|');
            sb.append(stack.get(i).fullMethod());
        }
        return sb.toString();
    }

    /**
     * 找到第一个"有意义"帧的索引 — 跳过栈顶常见的 JDK idle 帧。
     */
    private static int findFirstMeaningfulIndex(List<StackFrame> stack) {
        for (int i = 0; i < stack.size(); i++) {
            String fullMethod = stack.get(i).fullMethod();
            if (!IDLE_METHODS.contains(fullMethod)) {
                return i;
            }
        }
        return 0; // 如果全是 idle 帧，回退到栈顶
    }

    /**
     * 静态工具方法: 获取栈中第一个有意义的帧。
     * 供前端 / StackAggregateGroup 使用以显示有意义的代表帧。
     *
     * @param stack 完整堆栈帧列表
     * @return 第一个有意义的帧，如果没有则返回栈顶帧
     */
    public static StackFrame findFirstMeaningfulFrame(List<StackFrame> stack) {
        if (stack == null || stack.isEmpty()) return null;
        int idx = findFirstMeaningfulIndex(stack);
        return stack.get(idx);
    }
}
