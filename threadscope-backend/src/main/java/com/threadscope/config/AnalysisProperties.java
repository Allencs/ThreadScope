package com.threadscope.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 分析引擎的防护性配置。
 *
 * @param maxThreadsPerDump     单个 dump 允许的最大线程数，超出即拒绝解析
 * @param parseTimeoutSeconds   分析引擎的整体超时时间
 * @param cacheMaxTotalThreads  缓存中所有分析结果的线程总数上限（按权重驱逐）
 * @param cacheExpireMinutes    分析结果写入后的过期时间
 */
@ConfigurationProperties(prefix = "threadscope.analysis")
public record AnalysisProperties(
    @DefaultValue("50000") int maxThreadsPerDump,
    @DefaultValue("30") int parseTimeoutSeconds,
    @DefaultValue("150000") long cacheMaxTotalThreads,
    @DefaultValue("60") int cacheExpireMinutes
) {}
