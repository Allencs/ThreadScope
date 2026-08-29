package com.threadscope.service;

import com.threadscope.config.AnalysisProperties;
import com.threadscope.exception.AnalysisNotFoundException;
import com.threadscope.model.AnalysisResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 分析结果缓存服务 — 使用 Caffeine 高性能本地缓存。
 *
 * 驱逐策略按"线程数"加权而非按条目数：一个 5 万线程的分析结果内存占用
 * 可达数百 MB，按条目数限制无法防止堆被撑爆。
 */
@Service
public class AnalysisStorageService {

    private final Cache<String, AnalysisResult> cache;

    public AnalysisStorageService(AnalysisProperties properties) {
        this.cache = Caffeine.newBuilder()
            .maximumWeight(properties.cacheMaxTotalThreads())
            .weigher((String id, AnalysisResult result) -> Math.max(1, result.totalThreads()))
            .expireAfterWrite(properties.cacheExpireMinutes(), TimeUnit.MINUTES)
            .recordStats()
            .build();
    }

    public void store(String analysisId, AnalysisResult result) {
        cache.put(analysisId, result);
    }

    public AnalysisResult get(String analysisId) {
        return cache.getIfPresent(analysisId);
    }

    public AnalysisResult getOrThrow(String analysisId) {
        AnalysisResult result = cache.getIfPresent(analysisId);
        if (result == null) {
            throw new AnalysisNotFoundException(analysisId);
        }
        return result;
    }
}
