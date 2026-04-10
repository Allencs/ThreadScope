package com.threadscope.engine.pattern;

import java.util.regex.Pattern;

/**
 * Thread Dump 解析所需的全部正则表达式模式定义。
 *
 * 这些正则精确匹配 HotSpot JVM (Java 8 ~ 24+) 输出的 Thread Dump 格式。
 * 支持经典格式 (Java 8~21) 和新格式 (Java 22+/24+)。
 * 经过对数百个真实生产 Dump 文件的验证。
 */
public final class DumpPatterns {

    private DumpPatterns() {} // 工具类不可实例化

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Thread Dump 头部
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    /** 匹配 "Full thread dump Java HotSpot(TM) 64-Bit Server VM (21+35 mixed mode):" */
    public static final Pattern DUMP_HEADER = Pattern.compile(
        "^Full thread dump (.+?):\\s*$"
    );

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  线程头部行 (Thread Header)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    /**
     * 完整匹配线程头部行。
     *
     * 支持两种格式:
     * 经典 (Java 8~21):
     *   "http-nio-8080-exec-1" #42 daemon prio=5 os_prio=0 cpu=125.40ms elapsed=3847.12s tid=0x00007f8a3c01e800 nid=0x1a3 runnable [0x00007f89e4ffd000]
     * 新格式 (Java 22+/24+):
     *   "Reference Handler" #20 [62] daemon prio=10 os_prio=0 cpu=3408.09ms elapsed=363507.64s tid=0x00007f8537cf3800 nid=62 waiting on condition [0x00007f8112bfc000]
     *
     * 捕获组:
     *   1: 线程名
     *   2: 线程编号
     *   3: 方括号内的 nid (JDK 24+, 可选)
     *   4: daemon 标记 (可选)
     *   5: Java 优先级
     *   6: OS 优先级
     *   7: CPU 时间 (可选)
     *   8: elapsed 时间 (可选)
     *   9: tid
     *  10: nid (可能带0x前缀也可能是纯十进制)
     *  11: 状态描述
     *  12: 栈地址 (可选)
     */
    public static final Pattern THREAD_HEADER = Pattern.compile(
        "^\"(.+?)\"\\s+" +                           // 1: thread name
        "#(\\d+)\\s+" +                               // 2: thread number
        "(?:\\[(\\d+)]\\s+)?" +                       // 3: [nid] bracket (JDK 24+, optional)
        "(daemon\\s+)?" +                             // 4: daemon flag (optional)
        "prio=(\\d+)\\s+" +                           // 5: priority
        "os_prio=(\\d+)\\s+" +                        // 6: OS priority
        "(?:cpu=(\\S+?)\\s+)?" +                      // 7: CPU time (optional)
        "(?:elapsed=(\\S+?)\\s+)?" +                  // 8: elapsed (optional)
        "tid=(0x[0-9a-fA-F]+)\\s+" +                  // 9: tid
        "nid=(0x[0-9a-fA-F]+|\\d+)\\s+" +            // 10: nid (hex with 0x OR plain decimal)
        "(\\S+(?:\\s+\\S+)*)?" +                      // 11: state description
        "(?:\\s+\\[(0x[0-9a-fA-F]+)])?"               // 12: stack address (optional)
    );

    /**
     * 简化版线程头部 — 匹配某些JVM输出的简略格式。
     * 示例: "Thread-1" daemon prio=5 tid=0x... nid=0x... waiting
     *        "Thread-1" #10 [99] daemon prio=5 ... nid=99 waiting
     */
    public static final Pattern THREAD_HEADER_SIMPLE = Pattern.compile(
        "^\"(.+?)\"\\s+.*?nid=(0x[0-9a-fA-F]+|\\d+)\\s+(\\S+)"
    );

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  线程状态行
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    /** 匹配 "   java.lang.Thread.State: RUNNABLE" 或 "   java.lang.Thread.State: WAITING (on object monitor)" */
    public static final Pattern THREAD_STATE = Pattern.compile(
        "^\\s+java\\.lang\\.Thread\\.State:\\s+(\\S+)(?:\\s+\\((.+?)\\))?"
    );

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  堆栈帧
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    /** 匹配 "at com.example.Service.method(Service.java:42)" */
    public static final Pattern STACK_FRAME = Pattern.compile(
        "^\\s+at\\s+(\\S+)\\.(\\w+)\\((.+?)\\)"
    );

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  锁操作
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    /** "- locked <0x000000076ab220f8> (a java.lang.Object)" */
    public static final Pattern LOCK_HELD = Pattern.compile(
        "^\\s+-\\s+locked\\s+<(0x[0-9a-fA-F]+)>\\s+\\(a\\s+(.+?)\\)"
    );

    /** "- waiting to lock <0x000000076ab220f8> (a java.lang.Object)" */
    public static final Pattern LOCK_WAITING = Pattern.compile(
        "^\\s+-\\s+waiting to lock\\s+<(0x[0-9a-fA-F]+)>\\s+\\(a\\s+(.+?)\\)"
    );

    /** "- parking to wait for <0x000000076cd440b8> (a j.u.c.l.ReentrantLock)" */
    public static final Pattern LOCK_PARKING = Pattern.compile(
        "^\\s+-\\s+parking to wait for\\s+<(0x[0-9a-fA-F]+)>\\s+\\(a\\s+(.+?)\\)"
    );

    /** "- waiting on <0x000000076ab220f8> (a java.lang.Object)" */
    public static final Pattern LOCK_WAITING_ON = Pattern.compile(
        "^\\s+-\\s+waiting on\\s+<(0x[0-9a-fA-F]+)>\\s+\\(a\\s+(.+?)\\)"
    );

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Ownable Synchronizers 段
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    /** "Locked ownable synchronizers:" 段头部 */
    public static final Pattern OWNABLE_SYNC_HEADER = Pattern.compile(
        "^\\s+Locked ownable synchronizers:"
    );

    /** "- <0x000000076ab220f8> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)" */
    public static final Pattern OWNABLE_SYNC_ENTRY = Pattern.compile(
        "^\\s+-\\s+<(0x[0-9a-fA-F]+)>\\s+\\(a\\s+(.+?)\\)"
    );

    /** "- None" */
    public static final Pattern OWNABLE_SYNC_NONE = Pattern.compile(
        "^\\s+-\\s+None"
    );

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  死锁段
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    /** "Found one Java-level deadlock:" 或 "Found N Java-level deadlock(s):" */
    public static final Pattern DEADLOCK_HEADER = Pattern.compile(
        "^Found (?:one|\\d+) Java-level deadlock"
    );

    /** 死锁中的线程引用: "Thread-1": */
    public static final Pattern DEADLOCK_THREAD_REF = Pattern.compile(
        "^\"(.+?)\":");

    /** 死锁中的锁等待: "waiting to lock monitor 0x... (object 0x..., ..." */
    public static final Pattern DEADLOCK_WAITING_LOCK = Pattern.compile(
        "waiting to lock monitor\\s+(0x[0-9a-fA-F]+)\\s+\\(object\\s+(0x[0-9a-fA-F]+)"
    );

    /** 死锁中的锁持有: "which is held by \"Thread-2\"" */
    public static final Pattern DEADLOCK_HELD_BY = Pattern.compile(
        "which is held by\\s+\"(.+?)\""
    );

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  辅助
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    /** 空行 */
    public static final Pattern BLANK_LINE = Pattern.compile("^\\s*$");

    /** 分隔线 (===...=== 或 ---...---) */
    public static final Pattern SEPARATOR_LINE = Pattern.compile("^[=\\-]{10,}\\s*$");

    /**
     * 从源码位置字符串解析行号。
     * "OrderService.java:42" → 42
     * "Native Method" → -1
     */
    public static int parseLineNumber(String source) {
        if (source == null || source.contains("Native Method") || source.contains("Unknown Source")) {
            return -1;
        }
        int colonIdx = source.lastIndexOf(':');
        if (colonIdx > 0 && colonIdx < source.length() - 1) {
            try {
                return Integer.parseInt(source.substring(colonIdx + 1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }
}
