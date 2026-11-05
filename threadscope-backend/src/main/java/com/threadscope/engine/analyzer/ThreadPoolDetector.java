package com.threadscope.engine.analyzer;

import com.threadscope.model.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 线程池检测器 — 自动识别并分类主流框架的线程池。
 *
 * 识别策略: 线程名模式匹配 (按框架默认命名规则) → 未命中的按名称前缀聚合。
 * 空闲/忙碌按栈判定 (见 {@link FrameClassifier#isIdleWorker})，而不是按线程状态。
 */
public class ThreadPoolDetector {

    public static final String CATEGORY_WEB = "WEB";
    public static final String CATEGORY_RPC = "RPC";
    public static final String CATEGORY_DB = "DB";
    public static final String CATEGORY_MQ = "MQ";
    public static final String CATEGORY_CACHE = "CACHE";
    public static final String CATEGORY_SCHEDULER = "SCHEDULER";
    public static final String CATEGORY_ASYNC = "ASYNC";
    public static final String CATEGORY_EVENT_LOOP = "EVENT_LOOP";
    public static final String CATEGORY_VIRTUAL = "VIRTUAL";
    public static final String CATEGORY_JVM = "JVM";
    public static final String CATEGORY_LOGGING = "LOGGING";
    public static final String CATEGORY_CUSTOM = "CUSTOM";
    public static final String CATEGORY_ANONYMOUS = "ANONYMOUS";

    /** 不参与"线程池耗尽"判定的类别 — JVM 内部线程、虚拟线程 (无上限) */
    public static final Set<String> NON_EXHAUSTIBLE_CATEGORIES = Set.of(CATEGORY_JVM, CATEGORY_VIRTUAL);

    /** 只由通用词组成、看不出业务含义的线程名前缀 */
    private static final Pattern GENERIC_POOL_NAME = Pattern.compile(
        "(?i)^(my|custom|default|common)?[-_]?(thread|worker|pool|executor|async|task|job)s?([-_]?(thread|worker|pool|executor))?$");

    private record PoolPattern(Pattern pattern, String poolType, String category) {}

    private static PoolPattern p(String regex, String type, String category) {
        return new PoolPattern(Pattern.compile(regex), type, category);
    }

    /** 顺序敏感：具体模式在前，宽泛模式在后。group(1) 为池名。 */
    private static final List<PoolPattern> KNOWN_PATTERNS = List.of(
        // ── Web Server ──
        p("^((?:http|https|ajp|h2c?)\\S*?-exec)-\\d+$", "Tomcat", CATEGORY_WEB),
        // <Executor namePrefix="tomcat-exec-"> / "catalina-exec-"，Spring Boot 里也很常见
        p("(?i)^(tomcat-exec)-\\d+$", "Tomcat", CATEGORY_WEB),
        p("(?i)^(catalina-exec)-\\d+$", "Tomcat", CATEGORY_WEB),
        p("^((?:http|https|ajp)\\S*?-Acceptor)(?:-\\d+)?$", "Tomcat-Acceptor", CATEGORY_WEB),
        p("^((?:http|https|ajp)\\S*?-Poller)(?:-\\d+)?$", "Tomcat-Poller", CATEGORY_WEB),
        p("^(Catalina-utility)-\\d+$", "Tomcat-Utility", CATEGORY_WEB),
        p("^(qtp\\d+)-\\d+$", "Jetty", CATEGORY_WEB),
        p("^(XNIO-\\d+ task)-\\d+$", "Undertow", CATEGORY_WEB),
        p("^(XNIO-\\d+ I/O)-\\d+$", "Undertow-IO", CATEGORY_EVENT_LOOP),
        p("^(XNIO-\\d+ Accept)$", "Undertow-Acceptor", CATEGORY_WEB),
        p("^(vert\\.x-eventloop-thread)-\\d+$", "Vert.x-EventLoop", CATEGORY_EVENT_LOOP),
        p("^(vert\\.x-worker-thread)-\\d+$", "Vert.x-Worker", CATEGORY_WEB),
        p("^(vert\\.x-internal-blocking)-\\d+$", "Vert.x-InternalBlocking", CATEGORY_WEB),
        p("^(vert\\.x-acceptor-thread)-\\d+$", "Vert.x-Acceptor", CATEGORY_WEB),
        p("^(reactor-http-(?:nio|epoll|kqueue))-\\d+$", "Reactor-Netty", CATEGORY_EVENT_LOOP),

        // ── Spring ──
        p("^(task)-\\d+$", "Spring-@Async", CATEGORY_ASYNC),
        p("^(SimpleAsyncTaskExecutor)-\\d+$", "Spring-SimpleAsyncTaskExecutor", CATEGORY_ASYNC),
        p("^(ThreadPoolTaskExecutor)-\\d+$", "Spring-ThreadPoolTaskExecutor", CATEGORY_ASYNC),
        p("^(scheduling)-\\d+$", "Spring-Scheduling", CATEGORY_SCHEDULER),
        p("^(ThreadPoolTaskScheduler)-\\d+$", "Spring-Scheduling", CATEGORY_SCHEDULER),
        p("^(org\\.springframework\\.kafka\\.KafkaListenerEndpointContainer#\\d+)-\\d+-C-\\d+$", "Spring-Kafka-Listener", CATEGORY_MQ),
        p("^(org\\.springframework\\.amqp\\.rabbit\\.RabbitListenerEndpointContainer#\\d+)-\\d+$", "Spring-AMQP-Listener", CATEGORY_MQ),

        // ── Scheduler ──
        p("^(\\S+?_Worker)-\\d+$", "Quartz", CATEGORY_SCHEDULER),
        p("^(\\S+?_QuartzSchedulerThread)$", "Quartz-Scheduler", CATEGORY_SCHEDULER),
        p("^(xxl-job, executor JobThread)-\\d+-\\d+$", "XXL-Job-JobThread", CATEGORY_SCHEDULER),
        p("^(xxl-job, EmbedServer bizThreadPool)-\\d+$", "XXL-Job-EmbedServer", CATEGORY_SCHEDULER),
        p("^(xxl-job, admin JobTriggerPoolHelper-(?:fast|slow)TriggerPool)-\\d+$", "XXL-Job-Admin", CATEGORY_SCHEDULER),
        p("^(xxl-job, (?:executor|admin))\\b.*$", "XXL-Job-Internal", CATEGORY_SCHEDULER),
        p("^(Timer)-\\d+$", "java.util.Timer", CATEGORY_SCHEDULER),

        // ── Database Connection Pool ──
        p("^(HikariPool-\\d+) (?:housekeeper|connection adder|connection closer)$", "HikariCP", CATEGORY_DB),
        p("^(HikariPool-\\d+)-connection-\\d+$", "HikariCP", CATEGORY_DB),
        p("^(Druid-ConnectionPool-(?:Create|Destroy|Log))-\\d+$", "Druid", CATEGORY_DB),
        p("^(DBCP Connection).*$", "DBCP", CATEGORY_DB),

        // ── RPC Framework ──
        p("^(DubboServerHandler-\\S+)-thread-\\d+$", "Dubbo-Server", CATEGORY_RPC),
        p("^(DubboClientHandler-\\S+)-thread-\\d+$", "Dubbo-Client", CATEGORY_RPC),
        p("^(dubbo-protocol-\\S+)-thread-\\d+$", "Dubbo", CATEGORY_RPC),
        p("^(Dubbo-[\\w-]+?)-thread-\\d+$", "Dubbo", CATEGORY_RPC),
        p("^(NettyServer(?:Worker|Boss))-\\d+-\\d+$", "Dubbo-Netty", CATEGORY_EVENT_LOOP),
        p("^(NettyClientWorker)-\\d+-\\d+$", "Dubbo-Netty", CATEGORY_EVENT_LOOP),
        p("^(grpc-default-executor)-\\d+$", "gRPC", CATEGORY_RPC),
        p("^(grpc-(?:nio|default|epoll)-worker-ELG-\\d+)-\\d+$", "gRPC-EventLoop", CATEGORY_EVENT_LOOP),
        p("^(hystrix-[\\w-]+?)-\\d+$", "Hystrix", CATEGORY_RPC),
        p("^(HystrixTimer)-\\d+$", "Hystrix-Timer", CATEGORY_RPC),
        p("^(OkHttp) .+$", "OkHttp", CATEGORY_RPC),

        // ── Message Queue ──
        p("^(kafka-producer-network-thread).*$", "Kafka-Producer", CATEGORY_MQ),
        p("^(kafka-coordinator-heartbeat-thread).*$", "Kafka-Consumer", CATEGORY_MQ),
        p("^(AMQP Connection) .+$", "RabbitMQ-Connection", CATEGORY_MQ),
        p("^(ConsumeMessageThread)_.*$", "RocketMQ-Consumer", CATEGORY_MQ),
        p("^(NettyClient(?:Selector|WorkerThread|PublicExecutor))_\\d+$", "RocketMQ-Netty", CATEGORY_MQ),
        p("^(RocketmqClient).*$", "RocketMQ", CATEGORY_MQ),

        // ── Cache / Redis ──
        p("^(lettuce-\\w+)-\\d+-\\d+$", "Lettuce", CATEGORY_CACHE),
        p("^(redisson-\\w+)-\\d+-\\d+$", "Redisson", CATEGORY_CACHE),

        // ── Netty / Reactor generic ──
        p("^((?:nio|epoll|kqueue|default)EventLoopGroup-\\d+)-\\d+$", "Netty-EventLoop", CATEGORY_EVENT_LOOP),
        p("^(globalEventExecutor-\\d+)-\\d+$", "Netty-GlobalEventExecutor", CATEGORY_EVENT_LOOP),
        p("^(boundedElastic)-\\d+$", "Reactor-BoundedElastic", CATEGORY_ASYNC),
        p("^(parallel)-\\d+$", "Reactor-Parallel", CATEGORY_ASYNC),

        // ── Logging ──
        p("^(Log4j2-TF-\\d+-\\S+?)-\\d+$", "Log4j2-Async", CATEGORY_LOGGING),
        p("^(AsyncAppender-Worker)-.*$", "Logback-Async", CATEGORY_LOGGING),

        // ── Async / JUC ──
        p("^(ForkJoinPool\\.commonPool)-worker-\\d+$", "ForkJoinPool-Common", CATEGORY_ASYNC),
        p("^(ForkJoinPool[.-]\\S+)-worker-\\d+$", "ForkJoinPool", CATEGORY_ASYNC),
        p("^(ScheduledExecutor)-\\d+$", "ScheduledExecutor", CATEGORY_SCHEDULER),
        p("^(pool-\\d+-thread)-\\d+$", "ThreadPoolExecutor (未命名)", CATEGORY_ANONYMOUS),
        p("^(Thread)-\\d+$", "new Thread() (未命名)", CATEGORY_ANONYMOUS),

        // ── GC / JVM Internal ──
        p("^(GC Thread)#\\d+$", "GC", CATEGORY_JVM),
        p("^(G1 [\\w ]+?)(?:#\\d+)?$", "G1-GC", CATEGORY_JVM),
        p("^(ZGC [\\w ]+?)(?:#\\d+)?$", "ZGC", CATEGORY_JVM),
        p("^(C\\d+ CompilerThread)\\d+$", "JIT-Compiler", CATEGORY_JVM),
        p("^(VM Thread|VM Periodic Task Thread|Reference Handler|Finalizer|Signal Dispatcher|"
            + "Service Thread|Monitor Deflation Thread|Common-Cleaner|Notification Thread|"
            + "Attach Listener|Sweeper thread|DestroyJavaVM)$", "JVM-Internal", CATEGORY_JVM)
    );

    /**
     * 检测并分组线程池。
     */
    public List<ThreadPoolInfo> detect(List<ThreadInfo> threads) {
        Map<String, List<ThreadInfo>> poolGroups = new LinkedHashMap<>();
        Map<String, PoolPattern> poolTypes = new HashMap<>();
        List<ThreadInfo> unmatched = new ArrayList<>();

        for (ThreadInfo thread : threads) {
            // 虚拟线程按 jcmd 线程容器聚合 (ThreadPerTaskExecutor@xxx)
            if (thread.kind() == ThreadKind.VIRTUAL) {
                String poolName = "VirtualThreads" + (thread.container() != null ? " · " + shortContainer(thread.container()) : "");
                poolGroups.computeIfAbsent(poolName, k -> new ArrayList<>()).add(thread);
                poolTypes.putIfAbsent(poolName, new PoolPattern(null, "VirtualThread", CATEGORY_VIRTUAL));
                continue;
            }
            PoolPattern hit = null;
            String poolName = null;
            for (PoolPattern pp : KNOWN_PATTERNS) {
                Matcher m = pp.pattern().matcher(thread.name());
                if (m.matches()) {
                    hit = pp;
                    poolName = m.group(1);
                    break;
                }
            }
            if (hit == null) {
                unmatched.add(thread);
                continue;
            }
            // ForkJoinPool 中承载虚拟线程的是虚拟线程调度器
            if (thread.kind() == ThreadKind.CARRIER && "ForkJoinPool".equals(hit.poolType())) {
                hit = new PoolPattern(hit.pattern(), "VirtualThread-Scheduler", CATEGORY_VIRTUAL);
            }
            poolGroups.computeIfAbsent(poolName, k -> new ArrayList<>()).add(thread);
            PoolPattern existing = poolTypes.get(poolName);
            if (existing == null || CATEGORY_VIRTUAL.equals(hit.category())) {
                poolTypes.put(poolName, hit);
            }
        }

        groupByCommonPrefix(unmatched, poolGroups, poolTypes);

        return poolGroups.entrySet().stream()
            .filter(e -> e.getValue().size() >= 2 || isNamedSingletonPool(poolTypes.get(e.getKey())))
            .map(e -> toPoolInfo(e.getKey(), e.getValue(), poolTypes.get(e.getKey())))
            .sorted(Comparator.comparingInt(ThreadPoolInfo::totalThreads).reversed())
            .toList();
    }

    /** 单线程也值得展示的池 (附带治理建议) — 其余单线程分组不算池 */
    private static boolean isNamedSingletonPool(PoolPattern pp) {
        return pp != null && "java.util.Timer".equals(pp.poolType());
    }

    private ThreadPoolInfo toPoolInfo(String poolName, List<ThreadInfo> poolThreads, PoolPattern pp) {
        String type = pp != null ? pp.poolType() : "Custom";
        String category = pp != null ? pp.category() : CATEGORY_CUSTOM;

        Map<ThreadState, Integer> stateDist = poolThreads.stream()
            .collect(Collectors.groupingBy(
                ThreadInfo::state,
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
            ));
        int idle = (int) poolThreads.stream().filter(FrameClassifier::isIdleWorker).count();
        int busy = poolThreads.size() - idle;

        return new ThreadPoolInfo(
            poolName,
            type,
            category,
            poolThreads.size(),
            stateDist,
            poolThreads.stream().map(ThreadInfo::name).toList(),
            poolThreads.stream().map(ThreadInfo::key).toList(),
            idle,
            busy,
            governanceHints(poolName, type, category, poolThreads.size(), busy)
        );
    }

    /** 线程池治理建议 */
    private List<String> governanceHints(String poolName, String type, String category, int total, int busy) {
        List<String> hints = new ArrayList<>();
        if (CATEGORY_ANONYMOUS.equals(category)) {
            if (poolName.startsWith("pool-")) {
                hints.add("未命名线程池：使用了默认 ThreadFactory (pool-N-thread-M)，无法从线程名判断业务归属，排障时难以定位。"
                    + "建议通过 Guava ThreadFactoryBuilder.setNameFormat(\"order-sync-%d\")、"
                    + "Spring CustomizableThreadFactory 或 ThreadPoolTaskExecutor.setThreadNamePrefix 设置有业务含义的前缀。");
            } else {
                hints.add("通过 new Thread() 直接创建的未命名线程 (Thread-N)，既无法复用也无法从名字判断用途。"
                    + "建议改用命名线程池统一管理，或至少调用 Thread.setName 设置业务名称。");
            }
        }
        switch (type) {
            case "Spring-@Async" -> hints.add("Spring 默认的 @Async 线程池 (task-N) 被所有异步任务共享，单个慢任务会拖慢其他业务。"
                + "建议为不同业务声明独立的 ThreadPoolTaskExecutor 并设置命名前缀、容量与拒绝策略。");
            case "Spring-SimpleAsyncTaskExecutor" -> hints.add("SimpleAsyncTaskExecutor 每个任务新建一个线程、不复用也没有上限，"
                + "高并发下线程数会失控。建议改用 ThreadPoolTaskExecutor (或 JDK 21+ 开启虚拟线程)。");
            case "ForkJoinPool-Common" -> {
                if (busy > 0) {
                    hints.add("公共 ForkJoinPool 是 parallelStream 与 CompletableFuture 无指定线程池时的默认执行器，全 JVM 共享，"
                        + "大小仅为 CPU 核数-1。在其中执行阻塞 IO 会让其他使用者饥饿，建议为 IO 型异步任务传入独立的 Executor。");
                }
            }
            case "java.util.Timer" -> hints.add("java.util.Timer 单线程执行全部任务，任一任务抛出未捕获异常会导致整个 Timer 终止。"
                + "建议改用 ScheduledThreadPoolExecutor。");
            default -> { }
        }
        if (CATEGORY_CUSTOM.equals(category) && total >= 2 && GENERIC_POOL_NAME.matcher(poolName).matches()) {
            hints.add("线程名前缀 \"" + poolName + "\" 过于通用，建议包含业务语义 (如 order-callback-worker)。");
        }
        return hints;
    }

    /**
     * 基于线程名公共前缀的通用聚合策略。
     * 例如: "my-worker-1", "my-worker-2", "my-worker-3" → 池 "my-worker"
     */
    private void groupByCommonPrefix(
            List<ThreadInfo> threads,
            Map<String, List<ThreadInfo>> poolGroups,
            Map<String, PoolPattern> poolTypes) {

        Map<String, List<ThreadInfo>> prefixGroups = new LinkedHashMap<>();
        for (ThreadInfo thread : threads) {
            String prefix = extractPrefix(thread.name());
            if (prefix != null) {
                prefixGroups.computeIfAbsent(prefix, k -> new ArrayList<>()).add(thread);
            }
        }

        prefixGroups.forEach((prefix, group) -> {
            if (group.size() >= 2 && !poolGroups.containsKey(prefix)) {
                poolGroups.put(prefix, group);
                poolTypes.put(prefix, new PoolPattern(null, "Custom", CATEGORY_CUSTOM));
            }
        });
    }

    /**
     * 提取线程名前缀。
     * "my-worker-pool-3" → "my-worker-pool"
     * "thread-123" → "thread"
     */
    private String extractPrefix(String name) {
        String prefix = name.replaceAll("[\\-_#\\s]\\d+$", "");
        return prefix.equals(name) || prefix.isBlank() ? null : prefix;
    }

    /** "java.util.concurrent.ThreadPerTaskExecutor@5a07e868" → "ThreadPerTaskExecutor@5a07e868" */
    static String shortContainer(String container) {
        String c = container;
        int slash = c.lastIndexOf('/');
        if (slash >= 0) c = c.substring(slash + 1);
        int at = c.indexOf('@');
        String cls = at >= 0 ? c.substring(0, at) : c;
        int dot = cls.lastIndexOf('.');
        return (dot >= 0 ? cls.substring(dot + 1) : cls) + (at >= 0 ? c.substring(at) : "");
    }
}
