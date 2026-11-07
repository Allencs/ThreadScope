package com.threadscope.jfr;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.threadscope.config.JfrProperties;
import com.threadscope.engine.analyzer.CodeLayerClassifier;
import com.threadscope.exception.AnalysisNotFoundException;
import com.threadscope.exception.ApiException;
import com.threadscope.exception.DumpTooLargeException;
import com.threadscope.model.AnalysisBundle;
import com.threadscope.service.AnalysisOrchestrator;
import com.threadscope.service.AnalysisOrchestrator.DumpSource;
import com.threadscope.service.AnalysisStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
@Profile("!test")
public class JfrAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(JfrAnalysisService.class);
    private static final Duration INCOMING_TTL = Duration.ofHours(1);

    private final JfrProperties properties;
    private final JfrRecordingParser parser;
    /** 多个大文件同时解析时堆峰值成倍增长，超出的上传排队等待。 */
    private final Semaphore parsing;
    private final JfrReportRepository repository;
    private final AnalysisOrchestrator orchestrator;
    private final AnalysisStorageService dumpStorage;
    private final Cache<String, JfrReportRepository.StoredReport> cache = Caffeine.newBuilder()
        .maximumSize(20)
        .expireAfterAccess(30, TimeUnit.MINUTES)
        .build();
    private final Cache<String, Optional<JfrStackIndex>> indexes = Caffeine.newBuilder()
        .maximumSize(20)
        .expireAfterAccess(30, TimeUnit.MINUTES)
        .build();
    /** 大录制的分配栈索引能到上百 MB，只缓存最近打开的几份。 */
    private final Cache<String, Optional<MemoryState>> memory = Caffeine.newBuilder()
        .maximumSize(3)
        .expireAfterAccess(20, TimeUnit.MINUTES)
        .build();

    public JfrAnalysisService(JfrProperties properties, JfrReportRepository repository,
                              AnalysisOrchestrator orchestrator, AnalysisStorageService dumpStorage) {
        this.properties = properties;
        this.parser = new JfrRecordingParser(properties);
        this.parsing = new Semaphore(Math.max(1, properties.maxConcurrentParses()), true);
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.dumpStorage = dumpStorage;
    }

    @PostConstruct
    void cleanStaleIncoming() {
        purgeIncoming(Instant.now().minus(INCOMING_TTL));
    }

    public JfrDocuments.ReportSummary upload(MultipartFile file, String uploader) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("上传的 JFR 文件是空的");
        if (file.getSize() > properties.maxUploadBytes()) {
            throw new DumpTooLargeException("JFR 文件超过 " + properties.maxUploadMb() + "MB 上限");
        }
        String filename = file.getOriginalFilename() == null ? "recording.jfr" : file.getOriginalFilename();
        Path dir = properties.incomingDirectory();
        Files.createDirectories(dir);
        Path target = dir.resolve(UUID.randomUUID() + ".jfr");
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target);
            parsing.acquire();
            try {
                return analyze(target, filename, uploader);
            } finally {
                parsing.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待解析时被中断", e);
        } finally {
            deleteQuietly(target);
        }
    }

    public List<JfrDocuments.ReportSummary> list() {
        repository.deleteExpired(Instant.now());
        cache.invalidateAll();
        indexes.invalidateAll();
        return repository.list();
    }

    public JfrDocuments.ReportSummary get(String id) {
        JfrReportRepository.StoredReport stored = load(id);
        return sampleWindow(stored.summary(), stored.hotspot());
    }

    /** 标题上的时长用有采样的时间桶，避免被录制窗口外的事件拉得很长。 */
    private static JfrDocuments.ReportSummary sampleWindow(
            JfrDocuments.ReportSummary summary, JfrDocuments.HotspotDocument hotspot) {
        if (hotspot == null || hotspot.bucketSamples().isEmpty() || hotspot.bucketCount() <= 0) return summary;
        int first = -1;
        int last = -1;
        List<Long> buckets = hotspot.bucketSamples();
        for (int i = 0; i < buckets.size(); i++) {
            if (buckets.get(i) > 0) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) return summary;
        long span = Math.max(1, hotspot.recordingEndEpochMs() - hotspot.recordingStartEpochMs());
        int count = Math.max(1, hotspot.bucketCount());
        long start = hotspot.recordingStartEpochMs() + span * first / count;
        long end = hotspot.recordingStartEpochMs() + span * (last + 1) / count;
        return new JfrDocuments.ReportSummary(
            summary.id(), summary.filename(), start, Math.max(0, end - start),
            summary.sampleCount(), summary.threadDumpCount(), summary.dumpAnalysisId(),
            summary.createdAtEpochMs(), summary.expiresAtEpochMs(), summary.jvmVersion(), summary.createdBy());
    }

    /** from / to 是热点时间桶下标，区间 [from, to)；不传或覆盖整份录制时统计全部样本。 */
    public JfrDocuments.HotspotList hotspots(String id, String query, String sort, Integer limit,
                                             Integer from, Integer to) {
        int cap = limit == null ? properties.hotspotLimit() : limit;
        JfrDocuments.HotspotDocument hotspot = load(id).hotspot();
        return JfrHotspotQuery.list(hotspot, index(id, hotspot), query, sort, cap,
            JfrDocuments.BucketRange.of(from, to, hotspot.bucketCount()));
    }

    /** 旧版报告或录制里没有 jdk.CPULoad 时返回 null。 */
    public JfrDocuments.CpuTimeline cpu(String id) {
        return JfrCpuTimeline.build(load(id).hotspot());
    }

    public List<JfrDocuments.HotspotCaller> callers(String id, List<String> path, String basis, String query,
                                                    Integer from, Integer to) {
        JfrDocuments.HotspotDocument hotspot = load(id).hotspot();
        return JfrHotspotQuery.expand(hotspot, index(id, hotspot), path, basis, query,
            JfrDocuments.BucketRange.of(from, to, hotspot.bucketCount()));
    }

    private JfrStackIndex index(String id, JfrDocuments.HotspotDocument hotspot) {
        return indexes.get(id, key -> Optional.ofNullable(JfrStackIndex.of(hotspot))).orElse(null);
    }

    public JfrDocuments.ThreadOverview threads(String id) {
        JfrReportRepository.StoredReport stored = load(id);
        JfrDocuments.ReportSummary window = sampleWindow(stored.summary(), stored.hotspot());
        long start = window.recordingStartEpochMs() == null ? 0 : window.recordingStartEpochMs();
        return JfrThreadOverview.build(stored.threads(), start, start + window.durationMillis());
    }

    /** 旧版报告没有内存数据时返回 null。 */
    public JfrDocuments.MemoryOverview memory(String id) {
        JfrReportRepository.StoredReport stored = load(id);
        MemoryState state = memoryState(id);
        return state == null ? null : JfrMemoryOverview.build(state.doc(), stored.threads());
    }

    /** packages 是业务包前缀，按 business 排序时用来判断哪些帧是业务代码；为空时按 JDK / 已知框架之外推断。 */
    public JfrDocuments.AllocationList allocations(String id, String source, String query, String sort, Integer limit,
                                                   Integer from, Integer to, String type, List<String> packages) {
        MemoryState state = requireMemory(id);
        JfrDocuments.AllocationProfile profile = state.profile(source);
        if (profile == null) throw new IllegalArgumentException("这份录制没有分配调用栈");
        int cap = limit == null ? properties.hotspotLimit() : limit;
        return JfrAllocationQuery.list(profile, state.index(profile), query, sort, cap,
            JfrDocuments.BucketRange.of(from, to, profile.stacks().bucketCount()), type, classifier(packages));
    }

    public List<JfrDocuments.HotspotCaller> allocationCallers(String id, String source, List<String> path, String basis,
                                                              String query, Integer from, Integer to, String type,
                                                              List<String> packages) {
        MemoryState state = requireMemory(id);
        JfrDocuments.AllocationProfile profile = state.profile(source);
        if (profile == null) return List.of();
        return JfrAllocationQuery.expand(profile, state.index(profile), path, basis, query,
            JfrDocuments.BucketRange.of(from, to, profile.stacks().bucketCount()), type, classifier(packages));
    }

    /** group：stack / business；thread 按线程名包含匹配，可以填线程池前缀。 */
    public JfrDocuments.StallReport stalls(String id, String group, String thread, Integer from, Integer to,
                                           List<String> packages) {
        JfrDocuments.MemoryDocument doc = requireMemory(id).doc();
        return JfrStallQuery.report(doc, group, thread, JfrDocuments.BucketRange.of(from, to, doc.bucketCount()),
            classifier(packages));
    }

    private static CodeLayerClassifier classifier(List<String> packages) {
        return packages == null || packages.isEmpty() ? CodeLayerClassifier.inferring() : new CodeLayerClassifier(packages);
    }

    private MemoryState requireMemory(String id) {
        load(id);
        MemoryState state = memoryState(id);
        if (state == null) throw new IllegalArgumentException("这份报告由旧版解析生成，没有内存数据，请重新上传 .jfr");
        return state;
    }

    private MemoryState memoryState(String id) {
        return memory.get(id, key -> repository.findMemory(key).map(MemoryState::new)).orElse(null);
    }

    public JfrDocuments.PlatformThread thread(String id, long javaThreadId) {
        return load(id).threads().platform().stream()
            .filter(thread -> thread.javaThreadId() == javaThreadId)
            .findFirst()
            .orElseThrow(() -> new AnalysisNotFoundException("线程 " + javaThreadId));
    }

    public OpenDump openDump(String id) {
        JfrReportRepository.StoredReport report = load(id);
        if (report.summary().dumpAnalysisId() == null) {
            throw new IllegalArgumentException("这份录制没有可分析的线程 Dump");
        }
        if (dumpStorage.findBundle(report.summary().dumpAnalysisId()).isEmpty()) {
            AnalysisBundle bundle = repository.findBundle(id)
                .orElseThrow(() -> new IllegalArgumentException("这份录制没有可分析的线程 Dump"));
            dumpStorage.store(bundle);
        }
        return new OpenDump(report.summary().dumpAnalysisId(), report.summary().threadDumpCount());
    }

    /** 管理员可以删任何报告，其他用户只能删自己上传的。 */
    public void delete(String id, String actor, boolean admin) {
        JfrReportRepository.StoredReport report = repository.find(id)
            .orElseThrow(() -> new AnalysisNotFoundException(id));
        if (!admin && (actor == null || !actor.equals(report.summary().createdBy()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "只能删除自己上传的报告");
        }
        if (report.summary().dumpAnalysisId() != null) dumpStorage.evict(report.summary().dumpAnalysisId());
        repository.delete(id);
        cache.invalidate(id);
        indexes.invalidate(id);
        memory.invalidate(id);
    }

    private JfrDocuments.ReportSummary analyze(Path file, String filename, String uploader) throws IOException {
        JfrDocuments.ParseResult parsed = parser.parse(file);
        String id = UUID.randomUUID().toString();
        String dumpAnalysisId = null;
        AnalysisBundle bundle = null;
        if (!parsed.threadDumps().isEmpty()) {
            dumpAnalysisId = UUID.randomUUID().toString();
            try {
                List<DumpSource> sources = new ArrayList<>();
                for (int i = 0; i < parsed.threadDumps().size(); i++) {
                    sources.add(DumpSource.utf8("jfr-dump-" + (i + 1) + ".txt", parsed.threadDumps().get(i)));
                }
                bundle = orchestrator.analyzeBundle(dumpAnalysisId, filename, sources, AnalysisOrchestrator.AnalysisOptions.defaults());
                dumpStorage.store(bundle);
            } catch (RuntimeException e) {
                log.warn("Thread dump extracted from {} could not be analyzed: {}", filename, e.getMessage());
                dumpAnalysisId = null;
                bundle = null;
            }
        }
        Instant now = Instant.now();
        var summary = new JfrDocuments.ReportSummary(
            id, trim(filename),
            parsed.hotspot().recordingStartEpochMs(),
            parsed.durationMillis(),
            parsed.hotspot().totalSamples(),
            parsed.threadDumps().size(),
            dumpAnalysisId,
            now.toEpochMilli(),
            now.plus(Duration.ofDays(properties.retentionDays())).toEpochMilli(),
            parsed.summary().jvmVersion(),
            uploader
        );
        var stored = new JfrReportRepository.StoredReport(summary, parsed.summary(), parsed.hotspot(), parsed.threads(), bundle);
        repository.insert(stored, parsed.memory());
        repository.deleteExpired(now);
        cache.put(id, new JfrReportRepository.StoredReport(summary, parsed.summary(), parsed.hotspot(), parsed.threads(), null));
        log.info("Stored JFR report {} samples={} dumps={} allocation={} file={}", id, summary.sampleCount(),
            summary.threadDumpCount(), allocationSummary(parsed.memory()), filename);
        return summary;
    }

    private static String allocationSummary(JfrDocuments.MemoryDocument memory) {
        if (memory == null || memory.profiles().isEmpty()) return "none";
        return memory.profiles().stream()
            .map(p -> p.source() + ":" + p.events() + "events/" + p.stacks().stacks().size() + "stacks")
            .reduce((a, b) -> a + "," + b)
            .orElse("none");
    }

    private JfrReportRepository.StoredReport load(String id) {
        JfrReportRepository.StoredReport cached = cache.getIfPresent(id);
        if (cached != null) return cached;
        JfrReportRepository.StoredReport stored = repository.find(id)
            .orElseThrow(() -> new AnalysisNotFoundException(id));
        if (stored.summary().expiresAtEpochMs() < Instant.now().toEpochMilli()) {
            repository.delete(id);
            throw new AnalysisNotFoundException(id);
        }
        cache.put(id, stored);
        return stored;
    }

    private void purgeIncoming(Instant olderThan) {
        Path dir = properties.incomingDirectory();
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".jfr")).forEach(path -> {
                try {
                    if (Files.getLastModifiedTime(path).toInstant().isBefore(olderThan)) deleteQuietly(path);
                } catch (IOException e) {
                    log.warn("Failed to inspect stale JFR {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("Failed to clean incoming JFR directory {}", dir, e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temporary JFR {}", path, e);
        }
    }

    private static String trim(String filename) {
        String name = filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    public record OpenDump(String analysisId, int dumpCount) {}

    /** 分配栈索引按来源懒加载，第一次查询某个来源时才构建。 */
    private record MemoryState(JfrDocuments.MemoryDocument doc, Map<String, JfrStackIndex> indexes) {
        MemoryState(JfrDocuments.MemoryDocument doc) {
            this(doc, new ConcurrentHashMap<>());
        }

        /** source 为空时取第一种：有 TLAB 用 TLAB，否则用分配采样。 */
        JfrDocuments.AllocationProfile profile(String source) {
            return doc.profiles().stream()
                .filter(p -> source == null || source.isBlank() || p.source().equalsIgnoreCase(source))
                .findFirst()
                .orElse(null);
        }

        JfrStackIndex index(JfrDocuments.AllocationProfile profile) {
            return indexes.computeIfAbsent(profile.source(), k -> JfrStackIndex.of(profile.stacks()));
        }
    }
}
