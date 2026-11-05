package com.threadscope.model;

/**
 * 数据质量提示 — 输入 dump 的缺陷会让部分分析静默失效，必须明确告诉用户结论的可信边界。
 *
 * @param code       问题标识，如 "NO_LOCKED_SYNCHRONIZERS"
 * @param level      INFO = 能力受限；WARNING = 部分结论可能不准确
 * @param title      一句话说明
 * @param impact     影响了哪些分析
 * @param suggestion 如何补抓数据
 */
public record DataQualityIssue(
    String code,
    Level level,
    String title,
    String impact,
    String suggestion
) {
    public enum Level { INFO, WARNING }
}
