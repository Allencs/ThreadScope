package com.threadscope.service;

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

    private final ThreadDumpLexer lexer = new ThreadDumpLexer();
    private final ThreadSemanticParser semanticParser = new ThreadSemanticParser();
    private final DeadlockDetector deadlockDetector = new DeadlockDetector();
    private final LockGraphBuilder lockGraphBuilder = new LockGraphBuilder();
    private final ThreadPoolDetector threadPoolDetector = new ThreadPoolDetector();
    private final StackAggregator stackAggregator = new StackAggregator();
    private final MethodHotspotAnalyzer methodHotspotAnalyzer = new MethodHotspotAnalyzer();
    private final HealthChecker healthChecker = new HealthChecker();

    /**
     * 完整分析流程: 从 InputStream 到 AnalysisResult。
     */
    public AnalysisResult analyze(String analysisId, String fileName, InputStream inputStream) throws IOException {
        long startTime = System.currentTimeMillis();

        // ━━━ Step 1: 词法分析 (流式，单线程) ━━━
        log.info("[{}] Starting lexical analysis for: {}", analysisId, fileName);
        ThreadDumpLexer.LexerResult lexerResult = lexer.tokenize(inputStream);
        log.info("[{}] Lexer found {} thread blocks", analysisId, lexerResult.threadBlocks().size());

        // ━━━ Step 2: 语义解析 (Virtual Threads 并行) ━━━
        List<ThreadInfo> threads = parseThreadsConcurrently(lexerResult.threadBlocks());
        log.info("[{}] Parsed {} threads", analysisId, threads.size());

        // ━━━ Step 3: 多引擎并行分析 (Structured Concurrency 风格) ━━━
        return analyzeWithEngines(analysisId, fileName, lexerResult, threads, startTime);
    }

    /**
     * 从文本内容分析 (粘贴场景)。
     */
    public AnalysisResult analyzeFromText(String analysisId, String fileName, String content) throws IOException {
        long startTime = System.currentTimeMillis();

        ThreadDumpLexer.LexerResult lexerResult = lexer.tokenize(content);
        List<ThreadInfo> threads = parseThreadsConcurrently(lexerResult.threadBlocks());

        return analyzeWithEngines(analysisId, fileName, lexerResult, threads, startTime);
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

            // 等待所有分析完成
            DeadlockInfo deadlocks = deadlockFuture.get(10, TimeUnit.SECONDS);
            List<LockInfo> lockInfos = lockGraphFuture.get(10, TimeUnit.SECONDS);
            List<ThreadPoolInfo> threadPools = threadPoolFuture.get(10, TimeUnit.SECONDS);
            List<StackAggregateGroup> stackAggs = stackAggFuture.get(10, TimeUnit.SECONDS);
            List<MethodHotspot> hotspots = hotspotFuture.get(10, TimeUnit.SECONDS);

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
                healthReport
            );

        } catch (Exception e) {
            log.error("[{}] Analysis engine failure", analysisId, e);
            throw new RuntimeException("Analysis failed: " + e.getMessage(), e);
        }
    }
}
