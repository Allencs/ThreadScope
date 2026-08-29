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
 * 利用 Java 21 Virtual Threads 实现：
 * 1. 线程块的并行语义解析
 * 2. 多分析引擎的并行执行
 */
@Service
public class AnalysisOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AnalysisOrchestrator.class);

    private final ThreadDumpLexer lexer;
    private final ThreadSemanticParser semanticParser = new ThreadSemanticParser();
    private final MultiDumpSplitter dumpSplitter = new MultiDumpSplitter();
    private final DumpComparator dumpComparator = new DumpComparator();
    private final DeadlockDetector deadlockDetector = new DeadlockDetector();
    private final LockGraphBuilder lockGraphBuilder = new LockGraphBuilder();
    private final ThreadPoolDetector threadPoolDetector = new ThreadPoolDetector();
    private final StackAggregator stackAggregator = new StackAggregator();
    private final MethodHotspotAnalyzer methodHotspotAnalyzer = new MethodHotspotAnalyzer();
    private final HealthChecker healthChecker = new HealthChecker();

    private final int engineTimeoutSeconds;

    public AnalysisOrchestrator(AnalysisProperties properties) {
        this.lexer = new ThreadDumpLexer(properties.maxThreadsPerDump());
        this.engineTimeoutSeconds = properties.parseTimeoutSeconds();
    }

    /**
     * 完整分析流程: 从 InputStream 到 AnalysisResult。
     * 内容整体读入后走多 dump 切分逻辑 (上传大小已由 multipart 上限约束)。
     */
    public AnalysisResult analyze(String analysisId, String fileName, InputStream inputStream) throws IOException {
        String content = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return analyzeFromText(analysisId, fileName, content);
    }

    /**
     * 从文本内容分析 (粘贴/单文件场景)。
     * 文件内包含多个 "Full thread dump" 段时自动切分并做差分对比。
     */
    public AnalysisResult analyzeFromText(String analysisId, String fileName, String content) throws IOException {
        return analyzeContents(analysisId, fileName, List.of(content));
    }

    /**
     * 多文件分析 — 每个文件视为一份 (或多份) dump，按顺序对比。
     */
    public AnalysisResult analyzeContents(String analysisId, String fileName, List<String> contents) throws IOException {
        long startTime = System.currentTimeMillis();

        // ━━━ Step 1: 切分 dump 段 (跨文件展平) ━━━
        List<MultiDumpSplitter.DumpSegment> segments = new ArrayList<>();
        for (String content : contents) {
            segments.addAll(dumpSplitter.split(content));
        }
        log.info("[{}] Found {} dump segment(s) in: {}", analysisId, segments.size(), fileName);

        // ━━━ Step 2: 逐段词法 + 语义解析 ━━━
        List<ThreadDumpLexer.LexerResult> lexerResults = new ArrayList<>();
        List<List<ThreadInfo>> dumps = new ArrayList<>();
        for (MultiDumpSplitter.DumpSegment segment : segments) {
            ThreadDumpLexer.LexerResult lexerResult = lexer.tokenize(segment.content());
            lexerResults.add(lexerResult);
            dumps.add(parseThreadsConcurrently(lexerResult.threadBlocks()));
        }

        // ━━━ Step 3: 多 dump 差分对比 ━━━
        DumpComparison comparison = null;
        if (dumps.size() > 1) {
            comparison = dumpComparator.compare(
                dumps,
                segments.stream().map(MultiDumpSplitter.DumpSegment::timestamp).toList());
            log.info("[{}] Compared {} dumps: {} stuck threads", analysisId,
                dumps.size(), comparison.stuckThreads().size());
        }

        // ━━━ Step 4: 对最新一份 dump 做完整引擎分析 ━━━
        ThreadDumpLexer.LexerResult lastLexer = lexerResults.getLast();
        List<ThreadInfo> lastThreads = dumps.getLast();
        log.info("[{}] Parsed {} threads (latest dump)", analysisId, lastThreads.size());

        return analyzeWithEngines(analysisId, fileName, lastLexer, lastThreads, comparison, startTime);
    }

    /**
     * 利用 Virtual Threads 并行解析每个线程块。
     * 每个线程块独立解析，无共享状态，天然适合并行化。
     */
    private List<ThreadInfo> parseThreadsConcurrently(List<ThreadDumpLexer.RawThreadBlock> blocks) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ThreadInfo>> futures = blocks.stream()
                .map(block -> executor.submit(() -> semanticParser.parse(block)))
                .toList();

            return futures.stream()
                .map(f -> {
                    try {
                        return f.get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.warn("Failed to parse thread block", e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
        }
    }

    /**
     * 多分析引擎并行执行。
     */
    private AnalysisResult analyzeWithEngines(
            String analysisId,
            String fileName,
            ThreadDumpLexer.LexerResult lexerResult,
            List<ThreadInfo> threads,
            DumpComparison comparison,
            long startTime) {

        // 状态分布统计
        Map<ThreadState, Long> stateDistribution = threads.stream()
            .collect(Collectors.groupingBy(ThreadInfo::state, Collectors.counting()));

        // 使用 Virtual Threads 并行执行各分析引擎
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var deadlockFuture = executor.submit(() ->
                deadlockDetector.detect(threads, lexerResult.deadlockSection()));

            var lockGraphFuture = executor.submit(() ->
                lockGraphBuilder.buildLockGraph(threads));

            var threadPoolFuture = executor.submit(() ->
                threadPoolDetector.detect(threads));

            var stackAggFuture = executor.submit(() ->
                stackAggregator.aggregate(threads, 2));

            var hotspotFuture = executor.submit(() ->
                methodHotspotAnalyzer.analyze(threads, 20, null));

            List<Future<?>> engineFutures = List.of(
                deadlockFuture, lockGraphFuture, threadPoolFuture, stackAggFuture, hotspotFuture);

            // 等待所有分析完成 (超时可配置；超时/失败时取消其余任务，避免后台空转)
            DeadlockInfo deadlocks;
            List<LockInfo> lockInfos;
            List<ThreadPoolInfo> threadPools;
            List<StackAggregateGroup> stackAggs;
            List<MethodHotspot> hotspots;
            try {
                deadlocks = deadlockFuture.get(engineTimeoutSeconds, TimeUnit.SECONDS);
                lockInfos = lockGraphFuture.get(engineTimeoutSeconds, TimeUnit.SECONDS);
                threadPools = threadPoolFuture.get(engineTimeoutSeconds, TimeUnit.SECONDS);
                stackAggs = stackAggFuture.get(engineTimeoutSeconds, TimeUnit.SECONDS);
                hotspots = hotspotFuture.get(engineTimeoutSeconds, TimeUnit.SECONDS);
            } catch (Exception e) {
                engineFutures.forEach(f -> f.cancel(true));
                throw e;
            }

            // 健康检查 (依赖上面的结果)
            HealthReport healthReport = healthChecker.check(threads, deadlocks, lockInfos, threadPools);

            long parseTimeMs = System.currentTimeMillis() - startTime;
            log.info("[{}] Analysis complete in {}ms", analysisId, parseTimeMs);

            return new AnalysisResult(
                analysisId,
                fileName,
                lexerResult.jvmVersion(),
                Instant.now(),
                parseTimeMs,
                threads,
                stateDistribution,
                deadlocks,
                lockInfos,
                threadPools,
                hotspots,
                stackAggs,
                healthReport,
                comparison
            );

        } catch (Exception e) {
            log.error("[{}] Analysis engine failure", analysisId, e);
            throw new RuntimeException("Analysis failed: " + e.getMessage(), e);
        }
    }
}
