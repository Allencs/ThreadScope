package com.threadscope.controller;

import com.threadscope.dto.TopCorrelationRequest;
import com.threadscope.engine.analyzer.CallTreeBuilder;
import com.threadscope.engine.analyzer.CodeLayerClassifier;
import com.threadscope.engine.analyzer.LockGraphBuilder;
import com.threadscope.engine.analyzer.LockGraphViewBuilder;
import com.threadscope.engine.analyzer.TopHCorrelator;
import com.threadscope.model.*;
import com.threadscope.service.AnalysisStorageService;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 分析结果查询控制器 — 为前端各模块提供数据接口。
 * 所有接口均基于已完成分析的 analysisId 进行查询。
 */
@RestController
@RequestMapping("/api/v1/analysis/{analysisId}")
public class AnalysisController {

    private static final int MAX_PAGE_SIZE = 1000;
    private static final int MAX_BATCH_NAMES = 500;

    private final AnalysisStorageService storageService;
    private final CallTreeBuilder callTreeBuilder = new CallTreeBuilder();
    private final TopHCorrelator topHCorrelator = new TopHCorrelator();
    private final LockGraphViewBuilder lockGraphViewBuilder = new LockGraphViewBuilder();
    private final LockGraphBuilder lockGraphBuilder = new LockGraphBuilder();

    public AnalysisController(AnalysisStorageService storageService) {
        this.storageService = storageService;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  概览 (Dashboard)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getOverview(@PathVariable String analysisId) {
        AnalysisResult result = storageService.getOrThrow(analysisId);

        long daemonCount = result.threads().stream().filter(ThreadInfo::daemon).count();

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("analysisId", result.analysisId());
        overview.put("fileName", result.fileName());
        overview.put("jvmVersion", result.jvmVersion());
        overview.put("totalThreads", result.totalThreads());
        overview.put("daemonCount", daemonCount);
        overview.put("nonDaemonCount", result.totalThreads() - daemonCount);
        overview.put("parseTimeMs", result.parseTimeMs());
        overview.put("analyzedAt", result.analyzedAt());
        overview.put("stateDistribution", result.stateDistribution());
        overview.put("healthReport", result.healthReport());
        overview.put("deadlockCount", result.deadlocks() != null ? result.deadlocks().totalDeadlocks() : 0);
        overview.put("threadPoolCount", result.threadPools() != null ? result.threadPools().size() : 0);
        overview.put("lockContentionCount", result.lockInfos() != null ? result.lockInfos().size() : 0);
        overview.put("dumpCount", result.dumpCount());
        overview.put("dumpFormat", result.dumpFormat());
        overview.put("snapshotIndex", result.snapshotIndex());
        overview.put("snapshotTimestamp", result.snapshotTimestamp());
        overview.put("snapshots", snapshotRefs(analysisId));
        overview.put("dataQuality", result.dataQuality() != null ? result.dataQuality() : List.of());
        overview.put("virtualThreads", result.virtualThreads());
        overview.put("applicationName", result.applicationName());
        overview.put("businessPackages", result.businessPackages() != null ? result.businessPackages() : List.of());
        overview.put("suggestedBusinessPackages",
            result.suggestedBusinessPackages() != null ? result.suggestedBusinessPackages() : List.of());

        return ResponseEntity.ok(overview);
    }

    /** 同一次上传中的全部快照，供前端切换 */
    private List<Map<String, Object>> snapshotRefs(String analysisId) {
        return storageService.findBundle(analysisId)
            .map(bundle -> {
                List<Map<String, Object>> refs = new ArrayList<>();
                int count = bundle.snapshots().size();
                for (int i = 0; i < count; i++) {
                    AnalysisResult s = bundle.snapshots().get(i);
                    Map<String, Object> ref = new LinkedHashMap<>();
                    ref.put("index", i);
                    ref.put("analysisId", AnalysisBundle.snapshotId(bundle.analysisId(), i, count));
                    ref.put("timestamp", s.snapshotTimestamp());
                    ref.put("totalThreads", s.totalThreads());
                    ref.put("healthLevel", s.healthReport() != null ? s.healthReport().overallLevel() : null);
                    ref.put("deadlockCount", s.deadlocks() != null ? s.deadlocks().totalDeadlocks() : 0);
                    ref.put("latest", i == count - 1);
                    refs.add(ref);
                }
                return refs;
            })
            .orElse(List.of());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  锁等待关系图
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/lock-graph")
    public ResponseEntity<LockGraphView> getLockGraph(
            @PathVariable String analysisId,
            @RequestParam(defaultValue = "false") boolean includeConditionWaits) {
        AnalysisResult result = storageService.getOrThrow(analysisId);
        CodeLayerClassifier classifier = new CodeLayerClassifier(
            result.businessPackages() != null ? result.businessPackages() : List.of());
        return ResponseEntity.ok(lockGraphViewBuilder.build(
            result.threads(), result.deadlocks(), includeConditionWaits, classifier));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  多 Dump 对比
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/comparison")
    public ResponseEntity<DumpComparison> getComparison(@PathVariable String analysisId) {
        AnalysisResult result = storageService.getOrThrow(analysisId);
        if (result.comparison() == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(result.comparison());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  调用树 (火焰图数据源)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/calltree")
    public ResponseEntity<CallTreeNode> getCallTree(
            @PathVariable String analysisId,
            @RequestParam(required = false) String state) {
        AnalysisResult result = storageService.getOrThrow(analysisId);
        ThreadState filter = (state != null && !state.isBlank()) ? ThreadState.fromString(state) : null;
        return ResponseEntity.ok(callTreeBuilder.build(result.threads(), filter));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  top -H CPU 关联
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @PostMapping("/cpu-correlation")
    public ResponseEntity<TopHCorrelator.CorrelationResult> correlateCpu(
            @PathVariable String analysisId,
            @Valid @RequestBody TopCorrelationRequest request) {
        AnalysisResult result = storageService.getOrThrow(analysisId);
        return ResponseEntity.ok(topHCorrelator.correlate(request.topOutput(), result.threads()));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  线程列表 (支持筛选/搜索/分页)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/threads")
    public ResponseEntity<Map<String, Object>> getThreads(
            @PathVariable String analysisId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String nid,
            @RequestParam(required = false) String lockAddress,
            @RequestParam(required = false) String poolName,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false, defaultValue = "default") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {

        // 分页参数防护：page 至少为 1，size 限制在 [1, MAX_PAGE_SIZE]
        page = Math.max(1, page);
        size = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        AnalysisResult result = storageService.getOrThrow(analysisId);
        List<ThreadInfo> filtered = result.threads();

        // 按线程池过滤 (用线程标识而非线程名 — 重名线程不会串池)
        if (poolName != null && !poolName.isBlank()) {
            Set<String> poolThreadKeys = safeList(result.threadPools()).stream()
                .filter(p -> p.poolName().equals(poolName))
                .flatMap(p -> p.threadKeys().stream())
                .collect(Collectors.toSet());
            filtered = filtered.stream()
                .filter(t -> poolThreadKeys.contains(t.key()))
                .toList();
        }

        // 按线程类别过滤 (PLATFORM / VIRTUAL / CARRIER，可逗号分隔)
        if (kind != null && !kind.isBlank()) {
            Set<ThreadKind> kinds = Arrays.stream(kind.split(","))
                .map(s -> ThreadKind.valueOf(s.trim().toUpperCase()))
                .collect(Collectors.toSet());
            filtered = filtered.stream()
                .filter(t -> kinds.contains(t.kind()))
                .toList();
        }

        // 按状态过滤
        if (state != null && !state.isBlank()) {
            Set<ThreadState> states = Arrays.stream(state.split(","))
                .map(ThreadState::fromString)
                .collect(Collectors.toSet());
            filtered = filtered.stream()
                .filter(t -> states.contains(t.state()))
                .toList();
        }

        // 按 nid 精确搜索
        if (nid != null && !nid.isBlank()) {
            filtered = filtered.stream()
                .filter(t -> t.nid().equalsIgnoreCase(nid) ||
                            String.valueOf(t.nidDecimal()).equals(nid))
                .toList();
        }

        // 按锁地址搜索
        if (lockAddress != null && !lockAddress.isBlank()) {
            String addr = lockAddress.toLowerCase();
            filtered = filtered.stream()
                .filter(t -> t.lockActions().stream()
                    .anyMatch(a -> a.lockAddress().toLowerCase().contains(addr)) ||
                    t.ownableSynchronizers().stream()
                    .anyMatch(s -> s.toLowerCase().contains(addr)))
                .toList();
        }

        // 全文模糊搜索
        if (search != null && !search.isBlank()) {
            String query = search.toLowerCase();
            filtered = filtered.stream()
                .filter(t -> matchesSearch(t, query))
                .toList();
        }

        // 排序
        if ("stackDepth".equals(sort)) {
            filtered = filtered.stream()
                .sorted(Comparator.comparingInt((ThreadInfo t) -> t.stackTrace().size()).reversed())
                .toList();
        }

        // 分页
        int total = filtered.size();
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<ThreadInfo> pageData = filtered.subList(fromIndex, toIndex);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", total);
        response.put("page", page);
        response.put("size", size);
        response.put("threads", pageData);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/threads/{threadName}")
    public ResponseEntity<ThreadInfo> getThread(
            @PathVariable String analysisId,
            @PathVariable String threadName) {
        AnalysisResult result = storageService.getOrThrow(analysisId);
        return result.threads().stream()
            .filter(t -> t.name().equals(threadName))
            .findFirst()
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 按线程标识 (key) 或线程名查询单个线程。优先精确匹配 key — 线程名可能重名。
     * 用查询参数而不是路径变量：线程名可能含 "/" 等无法放进路径的字符。
     */
    @GetMapping("/thread")
    public ResponseEntity<ThreadInfo> getThreadByRef(
            @PathVariable String analysisId,
            @RequestParam String ref) {
        AnalysisResult result = storageService.getOrThrow(analysisId);
        return result.threads().stream()
            .filter(t -> t.key().equals(ref))
            .findFirst()
            .or(() -> result.threads().stream().filter(t -> t.name().equals(ref)).findFirst())
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 批量查询线程信息 — 根据线程名列表返回对应的 ThreadInfo。
     * 用于 Locks 页面展开锁实例时批量预加载 waiting threads。
     */
    @PostMapping("/threads/batch")
    public ResponseEntity<Map<String, Object>> getThreadsBatch(
            @PathVariable String analysisId,
            @RequestBody Map<String, List<String>> body) {
        AnalysisResult result = storageService.getOrThrow(analysisId);
        List<String> names = body.getOrDefault("names", List.of());
        if (names.size() > MAX_BATCH_NAMES) {
            throw new IllegalArgumentException("Batch size exceeds the maximum of " + MAX_BATCH_NAMES + " names");
        }
        Set<String> nameSet = new HashSet<>(names);

        List<ThreadInfo> matched = result.threads().stream()
            .filter(t -> nameSet.contains(t.name()))
            .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("threads", matched);
        response.put("total", matched.size());
        return ResponseEntity.ok(response);
    }

    /**
     * 批量查询线程摘要 — 仅返回列表展示所需的轻量字段 (state / daemon / stackDepth)。
     * 相比 /threads/batch 大幅减少响应体积 (去掉了完整堆栈与锁操作)。
     */
    @PostMapping("/threads/batch-summary")
    public ResponseEntity<Map<String, Object>> getThreadsBatchSummary(
            @PathVariable String analysisId,
            @RequestBody Map<String, List<String>> body) {
        AnalysisResult result = storageService.getOrThrow(analysisId);
        List<String> names = body.getOrDefault("names", List.of());
        Set<String> nameSet = new HashSet<>(names);

        List<ThreadSummary> summaries = result.threads().stream()
            .filter(t -> nameSet.contains(t.name()))
            .map(ThreadSummary::from)
            .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("summaries", summaries);
        response.put("total", summaries.size());
        return ResponseEntity.ok(response);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  锁分析
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/locks")
    public ResponseEntity<Map<String, Object>> getLocks(@PathVariable String analysisId) {
        AnalysisResult result = storageService.getOrThrow(analysisId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("lockInfos", result.lockInfos());
        response.put("deadlocks", result.deadlocks());

        return ResponseEntity.ok(response);
    }

    /**
     * 按地址查一把锁，包括没有等待者的持有锁。
     * 竞争列表只含有等待者的锁；从线程栈点进来时仍要能看到持有者。
     */
    @GetMapping("/locks/by-address")
    public ResponseEntity<LockInfo> getLockByAddress(
            @PathVariable String analysisId,
            @RequestParam String address) {
        AnalysisResult result = storageService.getOrThrow(analysisId);
        LockInfo info = lockGraphBuilder.lookup(result.threads(), address);
        if (info == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(info);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  线程池分析
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/thread-pools")
    public ResponseEntity<Map<String, Object>> getThreadPools(@PathVariable String analysisId) {
        AnalysisResult result = storageService.getOrThrow(analysisId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("pools", result.threadPools());

        return ResponseEntity.ok(response);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  相似堆栈聚合
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/stack-aggregations")
    public ResponseEntity<Map<String, Object>> getStackAggregations(
            @PathVariable String analysisId,
            @RequestParam(defaultValue = "2") int minGroupSize) {
        AnalysisResult result = storageService.getOrThrow(analysisId);

        List<StackAggregateGroup> filtered = safeList(result.stackAggregations()).stream()
            .filter(g -> g.threadCount() >= minGroupSize)
            .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("groups", filtered);
        response.put("totalGroups", filtered.size());

        return ResponseEntity.ok(response);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  方法热点
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/method-hotspots")
    public ResponseEntity<Map<String, Object>> getMethodHotspots(
            @PathVariable String analysisId,
            @RequestParam(defaultValue = "20") int topN) {
        AnalysisResult result = storageService.getOrThrow(analysisId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("hotspots", safeList(result.methodHotspots()).stream().limit(Math.max(0, topN)).toList());

        return ResponseEntity.ok(response);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  辅助方法
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static <T> List<T> safeList(List<T> list) {
        return list != null ? list : List.of();
    }

    private boolean matchesSearch(ThreadInfo thread, String query) {
        // 搜索线程名 / 线程标识
        if (thread.name().toLowerCase().contains(query)) return true;
        if (thread.key().toLowerCase().equals(query)) return true;
        // 搜索 tid/nid
        if (thread.tid() != null && thread.tid().toLowerCase().contains(query)) return true;
        if (thread.nid() != null && thread.nid().toLowerCase().contains(query)) return true;
        // 搜索堆栈中的类名、方法名、以及 "类名.方法名" 组合
        return thread.stackTrace().stream()
            .anyMatch(f -> {
                String cls = f.className().toLowerCase();
                String method = f.methodName().toLowerCase();
                if (cls.contains(query) || method.contains(query)) return true;
                // 支持 "ClassName.methodName" 格式搜索 (用简短类名)
                String simpleCls = cls.substring(cls.lastIndexOf('.') + 1);
                return (simpleCls + "." + method).contains(query);
            });
    }
}
