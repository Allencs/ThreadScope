package com.threadscope.model;

/**
 * 表示堆栈中的单个帧 (Stack Frame)。
 * 对应 Thread Dump 中 "at com.example.Service.method(File.java:42)" 格式。
 */
public record StackFrame(
    String className,
    String methodName,
    String source,       // "OrderService.java:42" 或 "Native Method"
    int lineNumber        // -1 表示 Native Method 或行号未知
) {
    /**
     * 返回完整的方法限定名: "com.example.Service.method"
     */
    public String fullMethod() {
        return className + "." + methodName;
    }

    /**
     * 是否是 JDK 内部方法 (java.*, javax.*, sun.*, jdk.*)
     */
    public boolean isJdkFrame() {
        return className.startsWith("java.") ||
               className.startsWith("javax.") ||
               className.startsWith("sun.") ||
               className.startsWith("jdk.");
    }

    /**
     * 是否是 Native Method。
     * 用后缀匹配 — source 可能带模块前缀，如 "pinpoint.agent/Native Method"。
     */
    public boolean isNative() {
        return source != null && source.endsWith("Native Method");
    }
}
