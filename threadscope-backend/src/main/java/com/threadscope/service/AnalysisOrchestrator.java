package com.threadscope.service;

import com.threadscope.config.AnalysisProperties;
import com.threadscope.engine.analyzer.*;
import com.threadscope.engine.parser.*;
import com.threadscope.model.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 分析编排器 — 协调解析引擎和多个分析引擎的执行流。
 *
 * 1. 识别格式 (jstack / jcmd JSON / jcmd 文本) 并切分为若干快照
 * 2. 每份快照都做完整的引擎分析 — 死锁或阻塞风暴可能只出现在前几份里
 * 3. 多快照之间做差分对比
 *
 * 利用 Java 21 Virtual Threads 并行解析线程块与并行执行各分析引擎。
 */
@Service
public class AnalysisOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AnalysisOrchestrator.class);
    private static final int SUGGESTED_PACKAGES = 6;

    /**
     * 一份输入文本。
     *
     * @param name             文件名
     * @param content          解码后的文本
     * @param charset          解码使用的编码
     * @param charsetConverted 是否从非 UTF-8 编码转换
     */
    public record DumpSource(String name, String content, String charset, boolean charsetConverted) {
        public static DumpSource utf8(String name, String content) {
            return new DumpSource(name, content, "UTF-8", false);
        }
    }

    /**
     * @param businessPackages 业务包前缀 (为空时使用配置的默认值，仍为空则自动推断)
     * @param applicationName  应用名 (用户在前端选择的应用配置)
     */
    public record AnalysisOptions(List<String> businessPackages, String applicationName) {
        public static AnalysisOptions defaults() {
            return new AnalysisOptions(List.of(), null);
        }
    }

    /** 单份快照的解析结果 */
    private record Segment(
        String timestamp,
        String jvmVersion,
        List<ThreadInfo> threads,
        List<String> deadlockSection,
        String format,
        boolean hasLockedSynchronizers,
        boolean stateInferred,
        int parseFailures,
        DumpSource source
    ) {}

    private record ParsedThreads(List<ThreadInfo> threads, int failures) {}

    private final ThreadDumpLexer lexer;
    private final JcmdThreadDumpParser jcmdParser;
    private final ThreadSemanticParser semanticParser = new ThreadSemanticParser();
    private final MultiDumpSplitter dumpSplitter = new MultiDumpSplitter();
    private final DumpComparator dumpComparator = new DumpComparator();
    private final DeadlockDetector deadlockDetector = new DeadlockDetector();
    private final LockGraphBuilder lockGraphBuilder = new LockGraphBuilder();
    private final ThreadPoolDetector threadPoolDetector = new ThreadPoolDetector();
    private final StackAggregator stackAggregator = new StackAggregator();
    private final MethodHotspotAnalyzer methodHotspotAnalyzer = new MethodHotspotAnalyzer();
    private final VirtualThreadAnalyzer virtualThreadAnalyzer = new VirtualThreadAnalyzer();
    private final DataQualityInspector dataQualityInspector = new DataQualityInspector();
    private final HealthChecker healthChecker;
    private final List<String> defaultBusinessPackages;

    private final int engineTimeoutSeconds;

    public AnalysisOrchestrator(AnalysisProperties properties) {
        this.lexer = new ThreadDumpLexer(properties.maxThreadsPerDump());
        this.jcmdParser = new JcmdThreadDumpParser(properties.maxThreadsPerDump());
        this.engineTimeoutSeconds = properties.parseTimeoutSeconds();
        this.healthChecker = new HealthChecker(properties.health());
        this.defaultBusinessPackages = properties.businessPackages();
    }

    /**
     * 完整分析流程: 从 InputStream 到 AnalysisResult (最新快照)。
     */
    public AnalysisResult analyze(String analysisId, String fileName, InputStream inputStream) throws IOException {
        DumpTextDecoder.Decoded decoded = DumpTextDecoder.decode(inputStream.readAllBytes(), null);
        return analyzeBundle(analysisId, fileName,
            List.of(new DumpSource(fileName, decoded.text(), decoded.charset(), decoded.fallback())),
            AnalysisOptions.defaults()).latest();
    }

    /**
     * 从文本内容分析 (粘贴/单文件场景)。
     * 文件内包含多个 "Full thread dump" 段时自动切分并做差分对比。
     */
    public AnalysisResult analyzeFromText(String analysisId, String fileName, String content) throws IOException {
        return analyzeContents(analysisId, fileName, List.of(content));
    }

    /**
     * 多文件分析 — 每个文件视为一份 (或多份) dump，按顺序对比。返回最新快照的结果。
     */
    public AnalysisResult analyzeContents(String analysisId, String fileName, List<String> contents) throws IOException {
        List<DumpSource> sources = contents.stream().map(c -> DumpSource.utf8(fileName, c)).toList();
        return analyzeBundle(analysisId, fileName, sources, AnalysisOptions.defaults()).latest();
    }

    /**
     * 完整分析：每份快照各自完整分析，并做跨快照差分。
     */
    public AnalysisBundle analyzeBundle(String analysisId, String fileName, List<DumpSource> sources,
                                        AnalysisOptions options) throws IOException {
        long startTime = System.currentTimeMillis();
        AnalysisOptions opts = options != null ? options : AnalysisOptions.defaults();

        // ━━━ Step 1: 识别格式并切分为快照 ━━━
        List<Segment> segments = new ArrayList<>();
        for (DumpSource source : sources) {
            segments.addAll(parseSource(source));
        }
        if (segments.isEmpty()) {
            segments.add(new Segment(null, null, List.of(), List.of(), AnalysisResult.FORMAT_JSTACK,
                false, false, 0, sources.isEmpty() ? DumpSource.utf8(fileName, "") : sources.getFirst()));
        }
        log.info("[{}] Found {} dump segment(s) in: {}", analysisId, segments.size(), fileName);

        // ━━━ Step 2: 多 dump 差分对比 ━━━
        DumpComparison comparison = null;
        if (segments.size() > 1) {
            comparison = dumpComparator.compare(
                segments.stream().map(Segment::threads).toList(),
                segments.stream().map(Segment::timestamp).toList());
            log.info("[{}] Compared {} dumps: {} stuck threads, restart={}", analysisId,
                segments.size(), comparison.stuckThreads().size(), comparison.jvmRestartSuspected());
        }

        // ━━━ Step 3: 代码归属配置 ━━━
        List<String> businessPackages = opts.businessPackages() != null && !opts.businessPackages().isEmpty()
            ? opts.businessPackages() : defaultBusinessPackages;
        CodeLayerClassifier classifier = new CodeLayerClassifier(businessPackages);
        List<String> suggested = classifier.suggestBusinessPackages(segments.getLast().threads(), SUGGESTED_PACKAGES);

        // ━━━ Step 4: 每份快照做完整引擎分析 ━━━
        List<AnalysisResult> results = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            String snapshotId = AnalysisBundle.snapshotId(analysisId, i, segments.size());
            results.add(analyzeWithEngines(snapshotId, fileName, segments.get(i), i, segments.size(),
                comparison, classifier, businessPackages, opts.applicationName(), suggested, startTime));
        }
        log.info("[{}] Analysis complete in {}ms ({} snapshot(s))", analysisId,
            System.currentTimeMillis() - startTime, results.size());
        return new AnalysisBundle(analysisId, List.copyOf(results));
    }

    private List<Segment> parseSource(DumpSource source) throws IOException {
        JcmdThreadDumpParser.Format jcmdFormat = JcmdThreadDumpParser.detect(source.content());
        if (jcmdFormat != null) {
            var parsed = jcmdParser.parse(source.content(), jcmdFormat);
            String format = jcmdFormat == JcmdThreadDumpParser.Format.JSON
                ? AnalysisResult.FORMAT_JCMD_JSON : AnalysisResult.FORMAT_JCMD_TEXT;
            boolean hasLocks = parsed.threads().stream().anyMatch(t -> !t.lockActions().isEmpty());
            return List.of(new Segment(parsed.timestamp(), parsed.jvmVersion(), ensureUniqueKeys(parsed.threads()),
                List.of(), format, hasLocks, parsed.stateInferred(), 0, source));
        }

        List<Segment> segments = new ArrayList<>();
        for (MultiDumpSplitter.DumpSegment segment : dumpSplitter.split(source.content())) {
            ThreadDumpLexer.LexerResult lexerResult = lexer.tokenize(segment.content());
            ParsedThreads parsed = parseThreadsConcurrently(lexerResult.threadBlocks());
            segments.add(new Segment(segment.timestamp(), lexerResult.jvmVersion(), ensureUniqueKeys(parsed.threads()),
                lexerResult.deadlockSection(), AnalysisResult.FORMAT_JSTACK, lexerResult.hasLockedSynchronizers(),
                false, parsed.failures(), source));
        }
        return segments;
    }

    /**
     * 利用 Virtual Threads 并行解析每个线程块。
     * 每个线程块独立解析，无共享状态，天然适合并行化。
     */
    private ParsedThreads parseThreadsConcurrently(List<ThreadDumpLexer.RawThreadBlock> blocks) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ThreadInfo>> futures = blocks.stream()
                .map(block -> executor.submit(() -> semanticParser.parse(block)))
                .toList();

            List<ThreadInfo> threads = new ArrayList<>(futures.size());
            int failures = 0;
            for (Future<ThreadInfo> f : futures) {
                try {
                    ThreadInfo t = f.get(5, TimeUnit.SECONDS);
                    if (t != null) threads.add(t);
                    else failures++;
                } catch (Exception e) {
                    log.warn("Failed to parse thread block", e);
                    failures++;
                }
            }
            return new ParsedThreads(threads, failures);
        }
    }

    /** 同一快照内线程标识去重 (极端情况下 tid 缺失导致标识冲突时追加序号) */
    private static List<ThreadInfo> ensureUniqueKeys(List<ThreadInfo> threads) {
        Map<String, Integer> seen = new HashMap<>();
        List<ThreadInfo> result = new ArrayList<>(threads.size());
        for (ThreadInfo t : threads) {
            int n = seen.merge(t.key(), 1, Integer::sum);
            result.add(n == 1 ? t : t.withKey(t.key() + "~" + n));
        }
        return List.copyOf(result);
    }

    /**
     * 多分析引擎并行执行。
     */
    private AnalysisResult analyzeWithEngines(
            String analysisId,
            String fileName,
            Segment segment,
            int snapshotIndex,
            int dumpCount,
            DumpComparison comparison,
            CodeLayerClassifier classifier,
            List<String> businessPackages,
            String applicationName,
            List<String> suggestedPackages,
            long startTime) {

        List<ThreadInfo> threads = segment.threads();
        int jvmMajor = JvmVersion.majorVersion(segment.jvmVersion());

        Map<ThreadState, Long> stateDistribution = threads.stream()
            .collect(Collectors.groupingBy(ThreadInfo::state, Collectors.counting()));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var deadlockFuture = executor.submit(() ->
                deadlockDetector.detect(threads, segment.deadlockSection()));
            var lockGraphFuture = executor.submit(() ->
                lockGraphBuilder.buildLockGraph(threads));
            var threadPoolFuture = executor.submit(() ->
                threadPoolDetector.detect(threads));
            var stackAggFuture = executor.submit(() ->
                stackAggregator.aggregate(threads, 2));
            var hotspotFuture = executor.submit(() ->
                methodHotspotAnalyzer.analyze(threads, 20, null));
            var virtualFuture = executor.submit(() ->
                virtualThreadAnalyzer.analyze(threads, jvmMajor, !AnalysisResult.FORMAT_JSTACK.equals(segment.format())));

            List<Future<?>> engineFutures = List.of(
                deadlockFuture, lockGraphFuture, threadPoolFuture, stackAggFuture, hotspotFuture, virtualFuture);

            // 等待所有分析完成 (超时可配置；超时/失败时取消其余任务，避免后台空转)
            DeadlockInfo deadlocks;
            List<LockInfo> lockInfos;
            List<ThreadPoolInfo> threadPools;
            List<StackAggregateGroup> stackAggs;
            List<MethodHotspot> hotspots;
            VirtualThreadReport virtualThreads;
            try {
                deadlocks = deadlockFuture.get(engineTimeoutSeconds, TimeUnit.SECONDS);
                lockInfos = lockGraphFuture.get(engineTimeoutSeconds, TimeUnit.SECONDS);
                threadPools = threadPoolFuture.get(engineTimeoutSeconds, TimeUnit.SECONDS);
                stackAggs = stackAggFuture.get(engineTimeoutSeconds, TimeUnit.SECONDS);
                hotspots = hotspotFuture.get(engineTimeoutSeconds, TimeUnit.SECONDS);
                virtualThreads = virtualFuture.get(engineTimeoutSeconds, TimeUnit.SECONDS);
            } catch (Exception e) {
                engineFutures.forEach(f -> f.cancel(true));
                throw e;
            }

            // 健康检查 (依赖上面的结果)
            HealthReport healthReport = healthChecker.check(
                threads, deadlocks, lockInfos, threadPools, virtualThreads, classifier);

            List<DataQualityIssue> dataQuality = dataQualityInspector.inspect(new DataQualityInspector.Context(
                segment.format(),
                threads,
                segment.hasLockedSynchronizers(),
                segment.stateInferred(),
                segment.parseFailures(),
                dumpCount,
                comparison != null && comparison.jvmRestartSuspected(),
                segment.source().charset(),
                segment.source().charsetConverted(),
                jvmMajor,
                virtualThreads
            ));

            return new AnalysisResult(
                analysisId,
                fileName,
                segment.jvmVersion(),
                Instant.now(),
                System.currentTimeMillis() - startTime,
                threads,
                stateDistribution,
                deadlocks,
                lockInfos,
                threadPools,
                hotspots,
                stackAggs,
                healthReport,
                comparison,
                snapshotIndex,
                segment.timestamp(),
                segment.format(),
                dataQuality,
                virtualThreads,
                applicationName,
                List.copyOf(businessPackages),
                suggestedPackages
            );

        } catch (Exception e) {
            log.error("[{}] Analysis engine failure", analysisId, e);
            throw new RuntimeException("Analysis failed: " + e.getMessage(), e);
        }
    }
}
