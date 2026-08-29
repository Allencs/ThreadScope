package com.threadscope.model;

import java.util.List;

/**
 * 调用树节点 — 所有线程的堆栈自底向上合并后的 trie 节点，用于火焰图渲染。
 *
 * @param name     短方法名 "ClassName.method"
 * @param value    经过该调用点的线程数
 * @param children 子调用 (更靠近栈顶的方法)，按 value 降序
 */
public record CallTreeNode(
    String name,
    int value,
    List<CallTreeNode> children
) {}
