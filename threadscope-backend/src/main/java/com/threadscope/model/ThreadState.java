package com.threadscope.model;

/**
 * Java线程状态枚举，映射JVM线程的六种基础状态。
 * 每种状态携带语义信息：前端展示用的严重等级与颜色标识。
 */
public enum ThreadState {
    NEW("default", "#bc8cff"),
    RUNNABLE("success", "#3fb950"),
    BLOCKED("danger", "#f85149"),
    WAITING("warning", "#d29922"),
    TIMED_WAITING("info", "#58a6ff"),
    TERMINATED("default", "#484f58"),
    UNKNOWN("default", "#8b949e");

    private final String severity;
    private final String color;

    ThreadState(String severity, String color) {
        this.severity = severity;
        this.color = color;
    }

    public String severity() { return severity; }
    public String color() { return color; }

    /**
     * 从Thread Dump中的状态字符串解析为枚举。
     * 支持带空格的格式如 "TIMED_WAITING" 和 "TIMED WAITING"。
     */
    public static ThreadState fromString(String s) {
        if (s == null || s.isBlank()) return UNKNOWN;
        return switch (s.trim().toUpperCase().replace(" ", "_")) {
            case "RUNNABLE"      -> RUNNABLE;
            case "BLOCKED"       -> BLOCKED;
            case "WAITING"       -> WAITING;
            case "TIMED_WAITING" -> TIMED_WAITING;
            case "NEW"           -> NEW;
            case "TERMINATED"    -> TERMINATED;
            default              -> UNKNOWN;
        };
    }
}
