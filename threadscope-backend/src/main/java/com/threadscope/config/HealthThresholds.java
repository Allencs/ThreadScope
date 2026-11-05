package com.threadscope.config;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 健康检查阈值 — 不同规模的应用基线差异很大 (网关常驻上千线程属正常)，需按部署环境调整。
 *
 * @param blockedRatioWarning   BLOCKED 线程占比告警线
 * @param blockedRatioCritical  BLOCKED 线程占比严重线
 * @param blockStormThreshold   同一把锁阻塞多少线程算阻塞风暴
 * @param poolExhaustionRatio   线程池忙碌比例达到多少算接近耗尽 (0~1)
 * @param poolMinThreads        线程池至少多少线程才参与耗尽判定
 * @param threadCountWarning    线程总数告警线
 * @param threadCountCritical   线程总数严重线
 * @param nonDaemonWarning      非 daemon 线程数告警线
 * @param deepStackFrames       栈深度告警线 (递归嫌疑)
 * @param ioBottleneckThreshold 同一业务调用点等待 IO 的线程数
 * @param rootBlockerMin        根阻塞线程至少拖住多少线程才报告
 * @param rootBlockerCritical   根阻塞线程拖住多少线程升级为严重
 * @param churnElapsedSeconds   存活不足多少秒算"新生线程"
 * @param churnMinCount         新生线程至少多少个才报告线程频繁创建
 * @param cpuHotspotMinThreads  同一位置至少多少个 RUNNABLE 线程才报告 CPU 热点
 */
public record HealthThresholds(
    @DefaultValue("0.05") double blockedRatioWarning,
    @DefaultValue("0.15") double blockedRatioCritical,
    @DefaultValue("5") int blockStormThreshold,
    @DefaultValue("0.9") double poolExhaustionRatio,
    @DefaultValue("5") int poolMinThreads,
    @DefaultValue("1000") int threadCountWarning,
    @DefaultValue("3000") int threadCountCritical,
    @DefaultValue("200") int nonDaemonWarning,
    @DefaultValue("500") int deepStackFrames,
    @DefaultValue("10") int ioBottleneckThreshold,
    @DefaultValue("5") int rootBlockerMin,
    @DefaultValue("20") int rootBlockerCritical,
    @DefaultValue("60") double churnElapsedSeconds,
    @DefaultValue("50") int churnMinCount,
    @DefaultValue("3") int cpuHotspotMinThreads
) {
    public static HealthThresholds defaults() {
        return new HealthThresholds(0.05, 0.15, 5, 0.9, 5, 1000, 3000, 200, 500, 10, 5, 20, 60, 50, 3);
    }
}
