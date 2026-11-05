package com.threadscope.model;

/**
 * 栈帧所属代码层。
 */
public enum CodeLayer {
    /** JDK / JVM 自身 (java.*, jdk.*, sun.* ...) */
    JDK,
    /** 三方框架与中间件 (Spring、Tomcat、Netty、驱动 ...) 以及运行时生成的代理类 */
    FRAMEWORK,
    /** 业务代码 (匹配配置的业务包前缀；未配置时为 JDK/框架之外的代码) */
    BUSINESS
}
