package com.threadscope.engine.analyzer;

import com.threadscope.model.CodeLayer;
import com.threadscope.model.ThreadState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeLayerClassifierTest {

    @Test
    void classifiesThreeLayers() {
        var c = new CodeLayerClassifier(List.of("com.alibaba.myteam"));
        assertEquals(CodeLayer.JDK, c.classify("java.util.HashMap"));
        assertEquals(CodeLayer.FRAMEWORK, c.classify("org.springframework.web.servlet.DispatcherServlet"));
        assertEquals(CodeLayer.FRAMEWORK, c.classify("com.alibaba.druid.pool.DruidDataSource"));
        assertEquals(CodeLayer.BUSINESS, c.classify("com.alibaba.myteam.order.OrderService"), "业务包优先于框架列表");
        assertEquals(CodeLayer.FRAMEWORK, c.classify("com.acme.Unknown"), "配置了业务包后，其余代码视为三方");
        assertEquals(CodeLayer.FRAMEWORK, c.classify("com.alibaba.myteam.OrderService$$SpringCGLIB$$0"), "代理类属于框架织入");
    }

    @Test
    void infersBusinessWhenNotConfigured() {
        var c = CodeLayerClassifier.inferring();
        assertEquals(CodeLayer.BUSINESS, c.classify("com.acme.order.OrderService"));
        assertEquals(CodeLayer.FRAMEWORK, c.classify("io.netty.channel.nio.NioEventLoop"));
    }

    @Test
    void suggestsBusinessPackages() {
        var threads = List.of(
            TestThreads.thread("a", ThreadState.RUNNABLE, "com.acme.order.svc.A.run", "org.springframework.X.y"),
            TestThreads.thread("b", ThreadState.RUNNABLE, "com.acme.order.dao.B.run"),
            TestThreads.thread("c", ThreadState.RUNNABLE, "com.acme.pay.C.run"));
        assertEquals(List.of("com.acme.order", "com.acme.pay"), CodeLayerClassifier.inferring().suggestBusinessPackages(threads, 5));
        assertEquals(List.of("com.acme.pay"), new CodeLayerClassifier(List.of("com.acme.order")).suggestBusinessPackages(threads, 5));
    }
}
