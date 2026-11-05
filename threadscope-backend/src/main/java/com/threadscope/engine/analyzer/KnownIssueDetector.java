package com.threadscope.engine.analyzer;

import com.threadscope.model.HealthReport.HealthLevel;
import com.threadscope.model.HealthReport.RiskItem;
import com.threadscope.model.StackFrame;
import com.threadscope.model.ThreadInfo;
import com.threadscope.model.ThreadState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 已知病症指纹库 — 把高频生产故障的堆栈特征做成规则，命中即给出"病名级"诊断。
 *
 * 与 HealthChecker 的统计类规则（比例/数量阈值）互补：
 * 这里每条规则都对应一个明确的故障模式和处置建议。
 */
public class KnownIssueDetector {

    private static final Set<ThreadState> WAITING_STATES = Set.of(ThreadState.WAITING, ThreadState.TIMED_WAITING);
    private static final Set<ThreadState> ANY_STATE = Set.of();

    /**
     * 指纹规则。
     *
     * @param category        风险分类标识
     * @param level           风险级别
     * @param minThreads      至少多少个线程命中才报告 (过滤瞬态噪音)
     * @param states          线程状态约束 (空 = 不限)
     * @param frameSignatures 栈帧特征 (任一帧包含任一特征即命中)
     * @param topFrameDepth   只在栈顶前 N 帧内匹配 (0 = 整个栈) — CPU 型病症必须停在栈顶附近才有意义
     * @param threadNamePrefix 线程名前缀约束 (null = 不限)
     * @param title           报告标题
     * @param advice          诊断与处置建议
     */
    private record Rule(
        String category,
        HealthLevel level,
        int minThreads,
        Set<ThreadState> states,
        List<String> frameSignatures,
        int topFrameDepth,
        String threadNamePrefix,
        String title,
        String advice
    ) {
        Rule(String category, HealthLevel level, int minThreads, Set<ThreadState> states,
             List<String> frameSignatures, String title, String advice) {
            this(category, level, minThreads, states, frameSignatures, 0, null, title, advice);
        }
    }

    private static final List<Rule> RULES = List.of(
        new Rule(
            "DB_POOL_EXHAUSTION", HealthLevel.CRITICAL, 3, WAITING_STATES,
            List.of(
                "com.zaxxer.hikari.pool.HikariPool.getConnection",
                "com.alibaba.druid.pool.DruidDataSource.takeLast",
                "com.alibaba.druid.pool.DruidDataSource.pollLast",
                "org.apache.commons.dbcp2.PoolingDataSource.getConnection"
            ),
            "数据库连接池耗尽",
            "多个线程在等待获取数据库连接。常见原因：慢 SQL 长时间占用连接、连接泄漏（借出未归还）、" +
            "或池容量小于并发需求。建议：排查数据库侧慢查询日志，检查连接是否在 finally 中归还，必要时增大 maximumPoolSize。"
        ),
        new Rule(
            "HTTP_POOL_EXHAUSTION", HealthLevel.WARNING, 3, WAITING_STATES,
            List.of(
                "org.apache.http.pool.AbstractConnPool.getPoolEntryBlocking",
                "org.apache.http.impl.conn.PoolingHttpClientConnectionManager.leaseConnection",
                "org.apache.hc.core5.pool.StrictConnPool.lease"
            ),
            "HTTP 客户端连接池耗尽",
            "多个线程在等待 HTTP 连接池出借连接。常见原因：下游服务响应慢导致连接被长期占用、" +
            "maxPerRoute/maxTotal 配置过小、或响应实体未关闭导致连接无法归还。建议结合下游耗时监控排查。"
        ),
        new Rule(
            "OBJECT_POOL_EXHAUSTION", HealthLevel.WARNING, 3, WAITING_STATES,
            List.of(
                "org.apache.commons.pool2.impl.GenericObjectPool.borrowObject",
                "org.apache.commons.pool.impl.GenericObjectPool.borrowObject"
            ),
            "对象池耗尽 (Jedis/Commons-Pool)",
            "多个线程阻塞在 commons-pool 的 borrowObject 上，典型场景是 Jedis 连接池耗尽。" +
            "建议：检查 Redis 命令耗时（大 key / hot key）、池对象是否归还、maxTotal 是否偏小。"
        ),
        new Rule(
            "REDIS_SLOW_RESPONSE", HealthLevel.WARNING, 5, WAITING_STATES,
            List.of(
                "io.lettuce.core.internal.Futures.awaitOrCancel",
                "io.lettuce.core.LettuceFutures.awaitOrCancel",
                "io.lettuce.core.protocol.AsyncCommand.await",
                "org.redisson.command.CommandAsyncService.get",
                "org.redisson.command.CommandSyncService.get",
                "org.redisson.RedissonObject.get"
            ),
            "大量线程同步等待 Redis 响应",
            "多个线程在 Lettuce/Redisson 同步调用上等待 Redis 返回。常见原因：大 key / 慢命令 (KEYS、HGETALL 大哈希)、" +
            "Redis 实例 CPU 打满、网络抖动，或单连接模型下某个慢命令阻塞了后续命令。建议查看 Redis slowlog 与实例负载。"
        ),
        new Rule(
            "CLASSLOADER_CONTENTION", HealthLevel.WARNING, 2,
            Set.of(ThreadState.BLOCKED),
            List.of("java.lang.ClassLoader.loadClass"),
            "类加载锁竞争",
            "多个线程 BLOCKED 在 ClassLoader.loadClass 上。常见于首次请求高峰触发大量类加载、" +
            "或反射/动态代理在热路径上反复触发类查找。建议：预热应用后再放量，检查热路径上的 Class.forName 调用。"
        ),
        new Rule(
            "LOG_APPENDER_CONTENTION", HealthLevel.WARNING, 3,
            Set.of(ThreadState.BLOCKED),
            List.of(
                "ch.qos.logback.core.OutputStreamAppender",
                "ch.qos.logback.core.rolling.RollingFileAppender",
                "org.apache.logging.log4j.core.appender",
                "org.apache.log4j.Category.callAppenders"
            ),
            "日志 Appender 锁竞争",
            "多个线程阻塞在同步日志写入上，日志成为吞吐瓶颈。常见诱因：日志量过大（如循环内打日志、" +
            "大对象 toString）、磁盘写入慢。建议：改用 AsyncAppender，降低热路径日志级别。"
        ),
        new Rule(
            "ASYNC_LOG_QUEUE_FULL", HealthLevel.WARNING, 2, ANY_STATE,
            List.of(
                "com.lmax.disruptor.MultiProducerSequencer.next",
                "org.apache.logging.log4j.core.async.AsyncLoggerDisruptor.enqueueLogMessageWhenQueueFull",
                "org.apache.logging.log4j.core.async.DefaultAsyncQueueFullPolicy",
                "ch.qos.logback.core.AsyncAppenderBase.put"
            ),
            "异步日志队列已满，业务线程被日志拖慢",
            "业务线程卡在向异步日志队列 (Log4j2 RingBuffer / Logback AsyncAppender 队列) 写入上 — 队列已满，" +
            "异步日志退化为同步阻塞。根因是日志产生速度超过磁盘写入速度。建议：降低日志量、" +
            "Log4j2 配置 log4j2.asyncQueueFullPolicy=Discard、Logback 设置 neverBlock=true 并调大 queueSize。"
        ),
        new Rule(
            "DB_SLOW_QUERY", HealthLevel.WARNING, 2,
            Set.of(ThreadState.RUNNABLE),
            List.of(
                "com.mysql.cj.protocol.a.NativeProtocol.read",
                "com.mysql.jdbc.MysqlIO.readFully",
                "oracle.net.ns.Packet.receive",
                "org.postgresql.core.PGStream.receive",
                "com.microsoft.sqlserver.jdbc.TDSChannel.read"
            ),
            "疑似慢 SQL (线程阻塞在数据库响应读取)",
            "多个 RUNNABLE 线程实际卡在读取数据库响应的 socket 上，说明 SQL 执行时间长。" +
            "建议：结合数据库侧慢查询日志定位具体 SQL，检查缺失索引或锁等待。"
        ),
        new Rule(
            "SYNC_DNS_RESOLUTION", HealthLevel.WARNING, 2,
            Set.of(ThreadState.RUNNABLE),
            List.of(
                "java.net.InetAddress.getAllByName",
                "java.net.Inet4AddressImpl.lookupAllHostAddr",
                "java.net.Inet6AddressImpl.lookupAllHostAddr"
            ),
            "同步 DNS 解析阻塞",
            "多个线程卡在 DNS 解析上。常见原因：DNS 服务器响应慢、JVM DNS 缓存 TTL 配置过短、" +
            "或每次请求都解析域名。建议：检查 networkaddress.cache.ttl 配置与 DNS 服务健康度。"
        ),
        new Rule(
            "REGEX_BACKTRACKING", HealthLevel.WARNING, 2,
            Set.of(ThreadState.RUNNABLE),
            List.of("java.util.regex.Pattern$"),
            6, null,
            "疑似正则表达式灾难性回溯",
            "多个 RUNNABLE 线程停在 java.util.regex 匹配内部。含嵌套量词的正则 (如 (a+)+、(.*)*) 遇到特定输入会指数级回溯，" +
            "单个请求即可吃满一个 CPU 核。建议：结合多份 dump 确认线程持续停在此处；改写正则消除嵌套量词、" +
            "限制输入长度，或改用占有量词/原子分组。"
        ),
        new Rule(
            "HASHMAP_INFINITE_LOOP", HealthLevel.WARNING, 1,
            Set.of(ThreadState.RUNNABLE),
            List.of(
                "java.util.HashMap.transfer",
                "java.util.HashMap$TreeNode.balanceInsertion",
                "java.util.HashMap$TreeNode.root",
                "java.util.HashMap$TreeNode.find",
                "java.util.HashMap$TreeNode.putTreeVal"
            ),
            3, null,
            "疑似 HashMap 并发修改导致的死循环",
            "线程停在 HashMap 扩容/红黑树操作内部。多线程并发写非线程安全的 HashMap 会破坏链表/树结构形成环，" +
            "导致线程在 get/put 中无限循环并持续占满 CPU。若多份 dump 中线程始终停在此处即可确认。" +
            "建议：改用 ConcurrentHashMap，或对共享 Map 的写操作加锁。"
        ),
        new Rule(
            "EXCEPTION_STORM", HealthLevel.WARNING, 3,
            Set.of(ThreadState.RUNNABLE),
            List.of("java.lang.Throwable.fillInStackTrace", "java.lang.StackTraceElement.initStackTraceElements"),
            4, null,
            "异常风暴 (大量线程正在构造异常栈)",
            "多个线程同时在填充异常堆栈，说明业务在高频抛出异常 — 异常栈填充开销很大，常导致 CPU 飙升。" +
            "建议：检查日志中高频异常，避免用异常做流程控制；高频业务异常可覆盖 fillInStackTrace 或关闭 writableStackTrace。"
        ),
        new Rule(
            "SECURE_RANDOM_BLOCKING", HealthLevel.WARNING, 2, ANY_STATE,
            List.of(
                "sun.security.provider.NativePRNG$RandomIO.readFully",
                "sun.security.provider.NativePRNG$RandomIO.implNextBytes",
                "sun.security.provider.SeedGenerator",
                "sun.security.provider.NativeSeedGenerator"
            ),
            "SecureRandom 熵源阻塞",
            "多个线程卡在 SecureRandom 读取熵源或争抢其内部锁上。Linux 下 /dev/random 熵不足时会阻塞，" +
            "容器环境尤其常见。建议：启动参数加 -Djava.security.egd=file:/dev/./urandom，避免在热路径反复 new SecureRandom()。"
        ),
        new Rule(
            "COMMON_POOL_STARVATION", HealthLevel.WARNING, 2, WAITING_STATES,
            List.of(
                "java.util.concurrent.CompletableFuture.join",
                "java.util.concurrent.CompletableFuture.get",
                "java.util.concurrent.CompletableFuture$Signaller.block",
                "java.util.concurrent.FutureTask.get"
            ),
            0, "ForkJoinPool.commonPool-worker",
            "公共 ForkJoinPool 线程在阻塞等待其他异步结果",
            "commonPool 的工作线程自身在 join/get 等待其他异步任务，而被等待的任务也排在同一个只有 (CPU 核数-1) 个线程的池里，" +
            "极易出现饥饿甚至互相等待卡死。建议：为 CompletableFuture 的阻塞型任务传入独立的 Executor，避免在异步回调中同步等待。"
        ),
        new Rule(
            "FUTURE_WAIT_NO_TIMEOUT", HealthLevel.INFO, 5,
            Set.of(ThreadState.WAITING),
            List.of(
                "java.util.concurrent.FutureTask.get",
                "java.util.concurrent.FutureTask.awaitDone",
                "java.util.concurrent.CompletableFuture.join",
                "java.util.concurrent.CompletableFuture.get",
                "java.util.concurrent.CountDownLatch.await"
            ),
            "大量线程无限期等待异步结果",
            "多个线程处于 WAITING (非 TIMED_WAITING) 等待 Future/CountDownLatch — 说明调用未设置超时，" +
            "一旦下游任务卡住，这些线程会永久挂起并占住所在线程池。建议使用 get(timeout)/orTimeout 并处理超时。"
        ),
        new Rule(
            "SPRING_SINGLETON_LOCK", HealthLevel.WARNING, 2,
            Set.of(ThreadState.BLOCKED),
            List.of("org.springframework.beans.factory.support.DefaultSingletonBeanRegistry.getSingleton"),
            "Spring 单例创建锁竞争",
            "多个线程 BLOCKED 在 Spring 单例注册表的全局锁上，通常是请求线程在运行期触发了懒加载 Bean 的创建，" +
            "或某个 Bean 初始化 (@PostConstruct) 耗时过长。建议：排查 @Lazy Bean 与 getBean 的运行期调用，缩短 Bean 初始化逻辑。"
        )
    );

    /**
     * 扫描所有线程，返回命中的病症风险项。
     */
    public List<RiskItem> detect(List<ThreadInfo> threads) {
        List<RiskItem> risks = new ArrayList<>();

        for (Rule rule : RULES) {
            List<String> matched = new ArrayList<>();
            for (ThreadInfo thread : threads) {
                if (!rule.states().isEmpty() && !rule.states().contains(thread.state())) continue;
                if (rule.threadNamePrefix() != null && !thread.name().startsWith(rule.threadNamePrefix())) continue;
                if (matchesAnySignature(thread, rule.frameSignatures(), rule.topFrameDepth())) {
                    matched.add(thread.name());
                }
            }
            if (matched.size() >= rule.minThreads()) {
                risks.add(new RiskItem(
                    rule.category(),
                    rule.level(),
                    matched.size() + " 个线程命中: " + rule.title(),
                    rule.advice(),
                    matched.stream().limit(10).toList()
                ));
            }
        }
        return risks;
    }

    private boolean matchesAnySignature(ThreadInfo thread, List<String> signatures, int depth) {
        List<StackFrame> stack = thread.stackTrace();
        int limit = depth > 0 ? Math.min(depth, stack.size()) : stack.size();
        for (int i = 0; i < limit; i++) {
            // 去掉模块前缀后做包含匹配
            String normalized = FrameClassifier.normalize(stack.get(i).fullMethod());
            for (String sig : signatures) {
                if (normalized.contains(sig)) return true;
            }
        }
        return false;
    }
}
