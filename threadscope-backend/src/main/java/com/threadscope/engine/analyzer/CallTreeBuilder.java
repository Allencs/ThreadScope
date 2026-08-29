package com.threadscope.engine.analyzer;

import com.threadscope.model.CallTreeNode;
import com.threadscope.model.StackFrame;
import com.threadscope.model.ThreadInfo;
import com.threadscope.model.ThreadState;

import java.util.*;

/**
 * 调用树构建器 — 把所有线程的堆栈自底向上合并成一棵 trie (火焰图数据)。
 *
 * 栈底 (Thread.run 等入口) 为树根方向，栈顶为叶子方向。
 * 宽度 = 经过该调用点的线程数，一眼看出线程都堆在哪条调用路径上。
 */
public class CallTreeBuilder {

    /** 单栈参与合并的最大帧数 (从栈底往上数)，防止深递归线程撑爆节点数 */
    private static final int MAX_DEPTH = 80;

    private static class MutableNode {
        final String name;
        int value;
        final Map<String, MutableNode> children = new LinkedHashMap<>();

        MutableNode(String name) {
            this.name = name;
        }
    }

    /**
     * 构建调用树。
     *
     * @param threads     所有线程
     * @param stateFilter 仅统计指定状态 (null = 全部)
     * @return 根节点 (name = "all threads"，value = 参与线程数)
     */
    public CallTreeNode build(List<ThreadInfo> threads, ThreadState stateFilter) {
        MutableNode root = new MutableNode("all");

        for (ThreadInfo thread : threads) {
            if (thread.stackTrace().isEmpty()) continue;
            if (stateFilter != null && thread.state() != stateFilter) continue;

            root.value++;
            MutableNode current = root;

            // stackTrace[0] 是栈顶 — 反向遍历，从栈底 (调用入口) 向上合并
            List<StackFrame> stack = thread.stackTrace();
            int from = Math.max(0, stack.size() - MAX_DEPTH);
            for (int i = stack.size() - 1; i >= from; i--) {
                String name = shortName(stack.get(i));
                MutableNode child = current.children.computeIfAbsent(name, MutableNode::new);
                child.value++;
                current = child;
            }
        }

        return toImmutable(root);
    }

    private CallTreeNode toImmutable(MutableNode node) {
        List<CallTreeNode> children = node.children.values().stream()
            .sorted(Comparator.comparingInt((MutableNode n) -> n.value).reversed())
            .map(this::toImmutable)
            .toList();
        return new CallTreeNode(node.name, node.value, children);
    }

    /** "com.example.service.OrderService" + "process" → "OrderService.process" */
    private String shortName(StackFrame frame) {
        String cls = frame.className();
        // 去掉模块前缀与包名，保留简短类名
        int slash = cls.indexOf('/');
        if (slash >= 0) cls = cls.substring(slash + 1);
        int lastDot = cls.lastIndexOf('.');
        if (lastDot >= 0) cls = cls.substring(lastDot + 1);
        return cls + "." + frame.methodName();
    }
}
