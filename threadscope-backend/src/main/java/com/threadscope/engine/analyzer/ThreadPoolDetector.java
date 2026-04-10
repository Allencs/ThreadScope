package com.threadscope.engine.analyzer;

import com.threadscope.model.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 线程池检测器 — 自动识别并分类主流框架的线程池。
 *
 * 识别策略: 基于线程名前缀模式匹配 (工业界通用方案)
 */
public class ThreadPoolDetector {

    /**
     * 线程池模式定义: (正则模式, 池类型名称, 池名提取组)
     */
    private record PoolPattern(Pattern pattern, String poolType) {}

    private static final List<PoolPattern> KNOWN_PATTERNS = List.of(
        // ── Web Server ──
        new PoolPattern(Pattern.compile("^(http-nio-\\d+-exec)-\\d+$"), "Tomcat"),
        new PoolPattern(Pattern.compile("^(http-nio-\\d+-Acceptor)-\\d+$"), "Tomcat-Acceptor"),
        new PoolPattern(Pattern.compile("^(http-nio-\\d+-Poller)$"), "Tomcat-Poller"),
        new PoolPattern(Pattern.compile("^(qtp\\d+)-\\d+$"), "Jetty"),
        new PoolPattern(Pattern.compile("^(reactor-http-nio)-\\d+$"), "Netty-Reactor"),
        new PoolPattern(Pattern.compile("^(nioEventLoopGroup-\\d+)-\\d+$"), "Netty-EventLoop"),

        // ── Database Connection Pool ──
        new PoolPattern(Pattern.compile("^(HikariPool-\\d+)-connection-\\d+$"), "HikariCP"),
        new PoolPattern(Pattern.compile("^(HikariPool-\\d+)-housekeeper$"), "HikariCP-Housekeeper"),
        new PoolPattern(Pattern.compile("^(Druid-ConnectionPool-\\S+)-\\d+$"), "Druid"),
        new PoolPattern(Pattern.compile("^(DBCP Connection).*$"), "DBCP"),

        // ── RPC Framework ──
        new PoolPattern(Pattern.compile("^(DubboServerHandler-\\S+)-thread-\\d+$"), "Dubbo-Server"),
        new PoolPattern(Pattern.compile("^(DubboClientHandler-\\S+)-thread-\\d+$"), "Dubbo-Client"),
        new PoolPattern(Pattern.compile("^(dubbo-protocol-\\S+)-thread-\\d+$"), "Dubbo"),
        new PoolPattern(Pattern.compile("^(grpc-default-executor)-\\d+$"), "gRPC"),

        // ── Async / Scheduling ──
        new PoolPattern(Pattern.compile("^(ForkJoinPool[.-]\\S+)-worker-\\d+$"), "ForkJoinPool"),
        new PoolPattern(Pattern.compile("^(ForkJoinPool\\.commonPool)-worker-\\d+$"), "ForkJoinPool-Common"),
        new PoolPattern(Pattern.compile("^(scheduling)-\\d+$"), "Spring-Scheduling"),
        new PoolPattern(Pattern.compile("^(pool-\\d+)-thread-\\d+$"), "ThreadPoolExecutor"),
        new PoolPattern(Pattern.compile("^(Timer)-\\d+$"), "Timer"),
        new PoolPattern(Pattern.compile("^(ScheduledExecutor)-\\d+$"), "ScheduledExecutor"),

        // ── Message Queue ──
        new PoolPattern(Pattern.compile("^(kafka-producer-network-thread).*$"), "Kafka-Producer"),
        new PoolPattern(Pattern.compile("^(kafka-coordinator-heartbeat-thread).*$"), "Kafka-Consumer"),
        new PoolPattern(Pattern.compile("^(RocketmqClient).*$"), "RocketMQ"),

        // ── GC / JVM Internal ──
        new PoolPattern(Pattern.compile("^(GC Thread)#\\d+$"), "GC"),
        new PoolPattern(Pattern.compile("^(G1 \\S+ Thread)#\\d+$"), "G1-GC"),
        new PoolPattern(Pattern.compile("^(VM Thread)$"), "JVM-Internal"),
        new PoolPattern(Pattern.compile("^(Reference Handler)$"), "JVM-Internal"),
        new PoolPattern(Pattern.compile("^(Finalizer)$"), "JVM-Internal"),
        new PoolPattern(Pattern.compile("^(Signal Dispatcher)$"), "JVM-Internal"),
        new PoolPattern(Pattern.compile("^(C\\d+ CompilerThread)\\d+$"), "JIT-Compiler")
    );

    /**
     * 检测并分组线程池。
     */
    public List<ThreadPoolInfo> detect(List<ThreadInfo> threads) {
        // 1. 对每个线程尝试匹配已知模式
        Map<String, List<ThreadInfo>> poolGroups = new LinkedHashMap<>();
        Map<String, String> poolTypes = new HashMap<>();
        Set<String> matched = new HashSet<>();

        for (ThreadInfo thread : threads) {
            for (PoolPattern pp : KNOWN_PATTERNS) {
                Matcher m = pp.pattern().matcher(thread.name());
                if (m.matches() || m.find()) {
                    String poolName = m.group(1);
                    poolGroups.computeIfAbsent(poolName, k -> new ArrayList<>()).add(thread);
                    poolTypes.put(poolName, pp.poolType());
                    matched.add(thread.name());
                    break;
                }
            }
        }

        // 2. 对未匹配的线程，尝试通用模式聚合 (基于名称前缀)
        List<ThreadInfo> unmatched = threads.stream()
            .filter(t -> !matched.contains(t.name()))
            .toList();
        groupByCommonPrefix(unmatched, poolGroups, poolTypes);

        // 3. 转换为 ThreadPoolInfo
        return poolGroups.entrySet().stream()
            .filter(e -> e.getValue().size() >= 2)  // 至少2个线程才算池
            .map(e -> {
                String poolName = e.getKey();
                List<ThreadInfo> poolThreads = e.getValue();
                Map<ThreadState, Integer> stateDist = poolThreads.stream()
                    .collect(Collectors.groupingBy(
                        ThreadInfo::state,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                    ));
                return new ThreadPoolInfo(
                    poolName,
                    poolTypes.getOrDefault(poolName, "Custom"),
                    poolThreads.size(),
                    stateDist,
                    poolThreads.stream().map(ThreadInfo::name).toList()
                );
            })
            .sorted(Comparator.comparingInt(ThreadPoolInfo::totalThreads).reversed())
            .toList();
    }

    /**
     * 基于线程名公共前缀的通用聚合策略。
     * 例如: "my-worker-1", "my-worker-2", "my-worker-3" → 池 "my-worker"
     */
    private void groupByCommonPrefix(
            List<ThreadInfo> threads,
            Map<String, List<ThreadInfo>> poolGroups,
            Map<String, String> poolTypes) {

        Map<String, List<ThreadInfo>> prefixGroups = new HashMap<>();

        for (ThreadInfo thread : threads) {
            String prefix = extractPrefix(thread.name());
            if (prefix != null) {
                prefixGroups.computeIfAbsent(prefix, k -> new ArrayList<>()).add(thread);
            }
        }

        prefixGroups.forEach((prefix, group) -> {
            if (group.size() >= 2) {
                poolGroups.put(prefix, group);
                poolTypes.put(prefix, "Custom");
            }
        });
    }

    /**
     * 提取线程名前缀。
     * "my-worker-pool-3" → "my-worker-pool"
     * "thread-123" → "thread"
     */
    private String extractPrefix(String name) {
        // 去除末尾的数字后缀
        String prefix = name.replaceAll("[\\-_]\\d+$", "");
        return prefix.equals(name) ? null : prefix;
    }
}
