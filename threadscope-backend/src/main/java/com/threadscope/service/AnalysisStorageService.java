package com.threadscope.service;

import com.threadscope.config.AnalysisProperties;
import com.threadscope.exception.AnalysisNotFoundException;
import com.threadscope.model.AnalysisBundle;
import com.threadscope.model.AnalysisResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 分析结果缓存服务 — 使用 Caffeine 高性能本地缓存。
 *
 * 以"一次上传"为缓存单元 (含全部快照)，避免某个快照被单独驱逐后快照切换出现断档。
 * 驱逐策略按"线程数"加权而非按条目数：一个 5 万线程的分析结果内存占用
 * 可达数百 MB，按条目数限制无法防止堆被撑爆。
 */
@Service
public class AnalysisStorageService {

    private final Cache<String, AnalysisBundle> cache;

    public AnalysisStorageService(AnalysisProperties properties) {
        this.cache = Caffeine.newBuilder()
            .maximumWeight(properties.cacheMaxTotalThreads())
            .weigher((String id, AnalysisBundle bundle) -> Math.max(1, bundle.totalThreads()))
            .expireAfterWrite(properties.cacheExpireMinutes(), TimeUnit.MINUTES)
            .recordStats()
            .build();
    }

    public void store(AnalysisBundle bundle) {
        cache.put(bundle.analysisId(), bundle);
    }

    public void evict(String analysisId) {
        if (analysisId != null) cache.invalidate(baseId(analysisId));
    }

    /** 单快照结果的存储 (兼容旧调用方) */
    public void store(String analysisId, AnalysisResult result) {
        cache.put(analysisId, new AnalysisBundle(analysisId, List.of(result)));
    }

    public AnalysisResult get(String analysisId) {
        return resolve(analysisId).orElse(null);
    }

    public AnalysisResult getOrThrow(String analysisId) {
        return resolve(analysisId).orElseThrow(() -> new AnalysisNotFoundException(analysisId));
    }

    /** 按任意快照 ID 找到其所属的整次分析 */
    public Optional<AnalysisBundle> findBundle(String analysisId) {
        return Optional.ofNullable(cache.getIfPresent(baseId(analysisId)));
    }

    private Optional<AnalysisResult> resolve(String analysisId) {
        if (analysisId == null) return Optional.empty();
        AnalysisBundle bundle = cache.getIfPresent(baseId(analysisId));
        if (bundle == null) return Optional.empty();
        int sep = analysisId.lastIndexOf(AnalysisBundle.SNAPSHOT_SEPARATOR);
        if (sep < 0) return Optional.of(bundle.latest());
        try {
            int index = Integer.parseInt(analysisId.substring(sep + AnalysisBundle.SNAPSHOT_SEPARATOR.length()));
            return index >= 0 && index < bundle.snapshots().size()
                ? Optional.of(bundle.snapshots().get(index))
                : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static String baseId(String analysisId) {
        int sep = analysisId.lastIndexOf(AnalysisBundle.SNAPSHOT_SEPARATOR);
        return sep >= 0 ? analysisId.substring(0, sep) : analysisId;
    }
}
