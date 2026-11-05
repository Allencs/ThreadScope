package com.threadscope.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 分析引擎配置。
 *
 * @param maxThreadsPerDump     单个 dump 允许的最大线程数，超出即拒绝解析
 * @param parseTimeoutSeconds   分析引擎的整体超时时间
 * @param cacheMaxTotalThreads  缓存中所有分析结果的线程总数上限（按权重驱逐）
 * @param cacheExpireMinutes    分析结果写入后的过期时间
 * @param businessPackages      默认业务包前缀 (上传时未指定则使用)，用于区分业务代码与框架代码
 * @param health                健康检查阈值
 */
@ConfigurationProperties(prefix = "threadscope.analysis")
public record AnalysisProperties(
    @DefaultValue("50000") int maxThreadsPerDump,
    @DefaultValue("30") int parseTimeoutSeconds,
    @DefaultValue("150000") long cacheMaxTotalThreads,
    @DefaultValue("60") int cacheExpireMinutes,
    @DefaultValue("") List<String> businessPackages,
    @DefaultValue HealthThresholds health
) {
    public AnalysisProperties {
        businessPackages = businessPackages == null ? List.of()
            : businessPackages.stream().filter(s -> s != null && !s.isBlank()).toList();
        if (health == null) health = HealthThresholds.defaults();
    }

    public static AnalysisProperties defaults() {
        return new AnalysisProperties(50_000, 30, 150_000, 60, List.of(), HealthThresholds.defaults());
    }
}
