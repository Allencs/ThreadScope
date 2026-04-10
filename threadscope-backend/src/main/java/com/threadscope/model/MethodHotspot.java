package com.threadscope.model;

import java.util.List;

/**
 * 方法热点 — 统计 Dump 中出现在栈顶最频繁的方法。
 */
public record MethodHotspot(
    String className,
    String methodName,
    int occurrences,
    double percentage,
    List<String> sampleThreads
) {
    public String fullMethod() {
        return className + "." + methodName;
    }
}
