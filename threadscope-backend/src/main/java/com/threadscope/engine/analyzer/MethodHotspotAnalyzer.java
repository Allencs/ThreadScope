package com.threadscope.engine.analyzer;

import com.threadscope.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 方法热点分析器 — 统计 Thread Dump 中出现在栈顶的方法频次。
 *
 * 栈顶方法代表该线程当前正在执行的代码位置。
 * 高频栈顶方法通常是CPU热点或IO瓶颈所在。
 */
public class MethodHotspotAnalyzer {

    /**
     * 分析方法热点。
     *
     * @param threads     所有线程
     * @param topN        返回Top N个热点
     * @param stateFilter 仅统计指定状态的线程 (null = 所有状态)
     */
    public List<MethodHotspot> analyze(List<ThreadInfo> threads, int topN, ThreadState stateFilter) {
        List<ThreadInfo> filtered = threads.stream()
            .filter(t -> !t.stackTrace().isEmpty())
            .filter(t -> stateFilter == null || t.state() == stateFilter)
            .toList();

        if (filtered.isEmpty()) return List.of();

        int totalFiltered = filtered.size();

        // 按栈顶方法分组统计
        Map<String, List<ThreadInfo>> topMethodGroups = filtered.stream()
            .collect(Collectors.groupingBy(ThreadInfo::topMethod));

        return topMethodGroups.entrySet().stream()
            .map(e -> {
                String fullMethod = e.getKey();
                List<ThreadInfo> group = e.getValue();

                int dotIdx = fullMethod.lastIndexOf('.');
                String className = dotIdx > 0 ? fullMethod.substring(0, dotIdx) : fullMethod;
                String methodName = dotIdx > 0 ? fullMethod.substring(dotIdx + 1) : fullMethod;

                return new MethodHotspot(
                    className,
                    methodName,
                    group.size(),
                    (double) group.size() / totalFiltered * 100,
                    group.stream().map(ThreadInfo::name).limit(5).toList()
                );
            })
            .sorted(Comparator.comparingInt(MethodHotspot::occurrences).reversed())
            .limit(topN)
            .toList();
    }
}
