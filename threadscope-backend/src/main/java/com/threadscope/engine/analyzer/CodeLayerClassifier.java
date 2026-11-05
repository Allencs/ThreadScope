package com.threadscope.engine.analyzer;

import com.threadscope.model.CodeLayer;
import com.threadscope.model.StackFrame;
import com.threadscope.model.ThreadInfo;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 栈帧三层分类器：JDK / 三方框架 / 业务代码。
 *
 * 判定顺序：配置的业务包前缀 → JDK 前缀 → 运行时代理类 → 已知框架前缀 → 其余。
 * 业务包前缀优先于框架列表 — 企业自研代码可能恰好落在 com.alibaba.* 之类的前缀下。
 * 未配置业务包时，JDK 与已知框架之外的代码一律视为业务代码 (推断)。
 */
public class CodeLayerClassifier {

    public static final List<String> JDK_PREFIXES = List.of(
        "java.", "javax.", "jdk.", "sun.", "com.sun.", "com.oracle.", "org.ietf.", "org.w3c.", "org.xml.", "org.omg."
    );

    public static final List<String> FRAMEWORK_PREFIXES = List.of(
        // Spring / Jakarta
        "org.springframework.", "jakarta.", "org.aspectj.", "org.aopalliance.",
        // Apache (Tomcat、HttpClient、Kafka、Dubbo、Commons、Log4j ...)
        "org.apache.",
        // Web / 网络
        "io.netty.", "io.undertow.", "org.xnio.", "org.jboss.", "org.eclipse.jetty.", "io.vertx.", "org.glassfish.",
        "io.grpc.", "okhttp3.", "okio.", "retrofit2.", "feign.", "io.github.openfeign.", "reactor.", "io.reactivex.",
        "rx.", "io.projectreactor.",
        // 数据访问
        "com.zaxxer.", "com.alibaba.druid.", "org.hibernate.", "org.mybatis.", "org.mybatis.spring.", "com.baomidou.",
        "tk.mybatis.", "com.github.pagehelper.", "com.mysql.", "oracle.", "org.postgresql.", "com.microsoft.sqlserver.",
        "org.mariadb.", "org.h2.", "org.hsqldb.", "com.mongodb.", "org.bson.", "io.lettuce.", "redis.clients.",
        "org.redisson.", "org.elasticsearch.", "co.elastic.", "com.datastax.", "org.jooq.", "org.flywaydb.", "liquibase.",
        // 中间件 / 微服务
        "com.alibaba.", "com.alipay.", "com.taobao.", "com.netflix.", "io.github.resilience4j.", "com.ctrip.framework.",
        "com.dianping.cat.", "io.seata.", "com.xxl.", "org.quartz.", "com.rabbitmq.", "org.apache.rocketmq.",
        "io.nats.", "com.github.benmanes.", "org.ehcache.", "net.sf.ehcache.",
        // 序列化 / 工具
        "com.fasterxml.", "com.google.", "org.yaml.", "org.json.", "net.minidev.", "org.joda.", "io.jsonwebtoken.",
        "org.bouncycastle.", "cn.hutool.", "org.mapstruct.", "org.modelmapper.", "lombok.",
        // 日志 / 可观测
        "ch.qos.logback.", "org.slf4j.", "io.micrometer.", "io.prometheus.", "io.opentelemetry.", "brave.", "zipkin2.",
        "io.jaegertracing.", "com.navercorp.pinpoint.", "org.skywalking.", "io.sentry.",
        // 字节码 / 代理 / 语言运行时
        "net.bytebuddy.", "net.sf.cglib.", "javassist.", "org.objectweb.asm.", "kotlin.", "kotlinx.", "scala.", "akka.",
        "groovy.", "org.codehaus.groovy.", "clojure.",
        // 模板 / 文档 / 测试
        "org.thymeleaf.", "freemarker.", "io.swagger.", "springfox.", "org.springdoc.", "org.junit.", "org.testng.",
        "org.mockito.", "org.jetbrains.", "org.eclipse.", "org.osgi.", "io.dropwizard.", "de.codecentric."
    );

    /** 运行时生成的代理类 — 类名本身可能带业务包名，但帧属于框架织入代码 */
    private static final List<String> PROXY_MARKERS = List.of(
        "$$EnhancerBySpringCGLIB$$", "$$SpringCGLIB$$", "$$EnhancerByCGLIB$$", "$$FastClassBySpringCGLIB$$",
        "$$FastClassByCGLIB$$", "$HibernateProxy$", "$$_javassist", "$ByteBuddy$", "$MockitoMock$"
    );

    private final List<String> businessPrefixes;

    public CodeLayerClassifier(Collection<String> businessPrefixes) {
        this.businessPrefixes = normalizePrefixes(businessPrefixes);
    }

    public static CodeLayerClassifier inferring() {
        return new CodeLayerClassifier(List.of());
    }

    public List<String> businessPrefixes() {
        return businessPrefixes;
    }

    public boolean hasConfiguredBusinessPrefixes() {
        return !businessPrefixes.isEmpty();
    }

    public CodeLayer classify(StackFrame frame) {
        return classify(frame.className());
    }

    public CodeLayer classify(String rawClassName) {
        String cls = stripModule(rawClassName);
        boolean proxy = PROXY_MARKERS.stream().anyMatch(cls::contains);
        if (!proxy) {
            for (String p : businessPrefixes) {
                if (cls.startsWith(p)) return CodeLayer.BUSINESS;
            }
        }
        for (String p : JDK_PREFIXES) {
            if (cls.startsWith(p)) return CodeLayer.JDK;
        }
        if (proxy || cls.startsWith("$Proxy") || cls.contains(".$Proxy")) return CodeLayer.FRAMEWORK;
        for (String p : FRAMEWORK_PREFIXES) {
            if (cls.startsWith(p)) return CodeLayer.FRAMEWORK;
        }
        return businessPrefixes.isEmpty() ? CodeLayer.BUSINESS : CodeLayer.FRAMEWORK;
    }

    public boolean isBusiness(StackFrame frame) {
        return classify(frame) == CodeLayer.BUSINESS;
    }

    /** 线程栈中第一个业务帧 (从栈顶开始)，没有则返回 null */
    public StackFrame firstBusinessFrame(ThreadInfo thread) {
        for (StackFrame f : thread.stackTrace()) {
            if (isBusiness(f)) return f;
        }
        return null;
    }

    /**
     * 推荐业务包 — 统计 JDK/框架之外出现最多的包前缀 (取前 3 段，如 com.acme.order)。
     * 按"涉及的线程数"排序，已被配置覆盖的前缀不再推荐。
     */
    public List<String> suggestBusinessPackages(List<ThreadInfo> threads, int limit) {
        CodeLayerClassifier inferring = inferring();
        Map<String, Integer> threadCountByPkg = new HashMap<>();
        for (ThreadInfo t : threads) {
            Set<String> seen = new HashSet<>();
            for (StackFrame f : t.stackTrace()) {
                String cls = stripModule(f.className());
                if (inferring.classify(cls) != CodeLayer.BUSINESS) continue;
                String pkg = packagePrefix(cls, 3);
                if (pkg != null) seen.add(pkg);
            }
            seen.forEach(p -> threadCountByPkg.merge(p, 1, Integer::sum));
        }
        return threadCountByPkg.entrySet().stream()
            .filter(e -> businessPrefixes.stream().noneMatch(bp -> (e.getKey() + ".").startsWith(bp)))
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(limit)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /** "com.acme.order.service.OrderService" → "com.acme.order" (类所在包不足 n 段时取全包) */
    static String packagePrefix(String className, int segments) {
        int lastDot = className.lastIndexOf('.');
        if (lastDot <= 0) return null;
        String pkg = className.substring(0, lastDot);
        String[] parts = pkg.split("\\.");
        if (parts.length == 0) return null;
        return String.join(".", Arrays.copyOf(parts, Math.min(segments, parts.length)));
    }

    private static List<String> normalizePrefixes(Collection<String> prefixes) {
        if (prefixes == null) return List.of();
        return prefixes.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> s.endsWith(".") || s.endsWith("$") ? s : s + ".")
            .distinct()
            .toList();
    }

    private static String stripModule(String className) {
        int slash = className.indexOf('/');
        return slash >= 0 ? className.substring(slash + 1) : className;
    }
}
