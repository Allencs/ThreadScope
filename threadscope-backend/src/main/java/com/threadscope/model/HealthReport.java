package com.threadscope.model;

import java.util.List;

/**
 * 健康报告 — 基于专家经验规则对 Thread Dump 进行风险评估。
 */
public record HealthReport(
    HealthLevel overallLevel,
    List<RiskItem> risks
) {
    public enum HealthLevel {
        HEALTHY, WARNING, CRITICAL
    }

    /**
     * 风险项 — 每项代表一个被检测到的问题。
     */
    public record RiskItem(
        String category,         // "DEADLOCK", "BLOCK_STORM", "POOL_EXHAUSTION", "CPU_SPIKE", "HIGH_BLOCKED_RATIO"
        HealthLevel level,
        String title,
        String description,
        List<String> affectedThreads
    ) {}
}
