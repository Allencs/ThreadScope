package com.threadscope.service;

import com.threadscope.model.AnalysisResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 分析结果缓存服务 — 使用 Caffeine 高性能本地缓存。
 * 上传分析后的结果在内存中保留指定时间，供前端各模块按需查询。
 */
@Service
public class AnalysisStorageService {

    private final Cache<String, AnalysisResult> cache;

    public AnalysisStorageService(
            @Value("${threadscope.analysis.cache-max-size:100}") int maxSize,
            @Value("${threadscope.analysis.cache-expire-minutes:60}") int expireMinutes) {
        this.cache = Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterAccess(expireMinutes, TimeUnit.MINUTES)
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
            throw new IllegalArgumentException("Analysis not found: " + analysisId + ". It may have expired.");
        }
        return result;
    }
}
