package com.threadscope.controller;

import com.threadscope.model.*;
import com.threadscope.service.AnalysisStorageService;

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

    private final AnalysisStorageService storageService;

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

        return ResponseEntity.ok(overview);
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
            @RequestParam(required = false, defaultValue = "default") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {

        AnalysisResult result = storageService.getOrThrow(analysisId);
        List<ThreadInfo> filtered = result.threads();

        // 按线程池名过滤
        if (poolName != null && !poolName.isBlank()) {
            Set<String> poolThreadNames = result.threadPools().stream()
                .filter(p -> p.poolName().equals(poolName))
                .flatMap(p -> p.threadNames().stream())
                .collect(Collectors.toSet());
            filtered = filtered.stream()
                .filter(t -> poolThreadNames.contains(t.name()))
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
     * 批量查询线程信息 — 根据线程名列表返回对应的 ThreadInfo。
     * 用于 Locks 页面展开锁实例时批量预加载 waiting threads。
     */
    @PostMapping("/threads/batch")
    public ResponseEntity<Map<String, Object>> getThreadsBatch(
            @PathVariable String analysisId,
            @RequestBody Map<String, List<String>> body) {
        AnalysisResult result = storageService.getOrThrow(analysisId);
        List<String> names = body.getOrDefault("names", List.of());
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

        List<StackAggregateGroup> filtered = result.stackAggregations().stream()
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
        response.put("hotspots", result.methodHotspots().stream().limit(topN).toList());

        return ResponseEntity.ok(response);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  辅助方法
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private boolean matchesSearch(ThreadInfo thread, String query) {
        // 搜索线程名
        if (thread.name().toLowerCase().contains(query)) return true;
        // 搜索 tid/nid
        if (thread.tid().toLowerCase().contains(query)) return true;
        if (thread.nid().toLowerCase().contains(query)) return true;
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
