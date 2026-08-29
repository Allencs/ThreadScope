package com.threadscope.engine.analyzer;

import com.threadscope.model.HealthReport.HealthLevel;
import com.threadscope.model.HealthReport.RiskItem;
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

    /**
     * 指纹规则。
     *
     * @param category        风险分类标识
     * @param level           风险级别
     * @param minThreads      至少多少个线程命中才报告 (过滤瞬态噪音)
     * @param states          线程状态约束 (空 = 不限)
     * @param frameSignatures 栈帧特征 (任一帧包含任一特征即命中)
     * @param title           报告标题
     * @param advice          诊断与处置建议
     */
    private record Rule(
        String category,
        HealthLevel level,
        int minThreads,
        Set<ThreadState> states,
        List<String> frameSignatures,
        String title,
        String advice
    ) {}

    private static final List<Rule> RULES = List.of(
        new Rule(
            "DB_POOL_EXHAUSTION", HealthLevel.CRITICAL, 3,
            Set.of(ThreadState.WAITING, ThreadState.TIMED_WAITING),
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
            "HTTP_POOL_EXHAUSTION", HealthLevel.WARNING, 3,
            Set.of(ThreadState.WAITING, ThreadState.TIMED_WAITING),
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
            "OBJECT_POOL_EXHAUSTION", HealthLevel.WARNING, 3,
            Set.of(ThreadState.WAITING, ThreadState.TIMED_WAITING),
            List.of(
                "org.apache.commons.pool2.impl.GenericObjectPool.borrowObject",
                "org.apache.commons.pool.impl.GenericObjectPool.borrowObject"
            ),
            "对象池耗尽 (Jedis/Commons-Pool)",
            "多个线程阻塞在 commons-pool 的 borrowObject 上，典型场景是 Jedis 连接池耗尽。" +
            "建议：检查 Redis 命令耗时（大 key / hot key）、池对象是否归还、maxTotal 是否偏小。"
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
                if (matchesAnySignature(thread, rule.frameSignatures())) {
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

    private boolean matchesAnySignature(ThreadInfo thread, List<String> signatures) {
        return thread.stackTrace().stream().anyMatch(frame -> {
            String full = frame.fullMethod();
            // 去掉模块前缀后做包含匹配
            int slash = full.indexOf('/');
            String normalized = slash >= 0 ? full.substring(slash + 1) : full;
            for (String sig : signatures) {
                if (normalized.contains(sig)) return true;
            }
            return false;
        });
    }
}
