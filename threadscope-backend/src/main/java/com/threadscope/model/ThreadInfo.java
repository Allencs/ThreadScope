package com.threadscope.model;

import java.util.List;

/**
 * 单个线程的完整信息 — 解析后的核心数据结构。
 *
 * @param name               线程名称 (可能重名，不能作为唯一标识)
 * @param threadNumber       Java 线程 ID (#42)，同一 JVM 内唯一且单调递增、不复用
 * @param daemon             是否为守护线程
 * @param priority           Java线程优先级
 * @param osPriority         OS线程优先级
 * @param cpuTime            CPU消耗时间 (如 "125.40ms")
 * @param elapsed            线程存活时间 (如 "3847.12s")
 * @param tid                线程ID (JVM内部, hex格式 "0x00007f...")
 * @param nid                OS线程ID (hex格式 "0x1a3", 用于 top -H 映射)
 * @param nidDecimal         OS线程ID (十进制, 方便搜索)
 * @param state              线程状态
 * @param stateDetail        状态补充说明 (如 "on object monitor")
 * @param stackAddress       线程栈地址
 * @param stackTrace         完整堆栈帧列表
 * @param lockActions        所有锁操作 (持有/等待)
 * @param ownableSynchronizers 可拥有同步器列表
 * @param key                单份 dump 内唯一的线程标识 (见 {@link #identityKey})
 * @param kind               平台线程 / 虚拟线程 / 载体线程
 * @param carrier            VIRTUAL: 所挂载的载体线程标识；CARRIER: 正在承载的虚拟线程标识 (如 "#21")
 * @param container          jcmd 输出的线程容器 (如 ThreadPerTaskExecutor@xxx)，jstack 无此信息
 * @param stateInferred      状态是否由栈顶推断 (JDK 21 的 jcmd JSON 不输出线程状态)
 */
public record ThreadInfo(
    String name,
    long threadNumber,
    boolean daemon,
    int priority,
    int osPriority,
    String cpuTime,
    String elapsed,
    String tid,
    String nid,
    long nidDecimal,
    ThreadState state,
    String stateDetail,
    String stackAddress,
    List<StackFrame> stackTrace,
    List<LockAction> lockActions,
    List<String> ownableSynchronizers,
    String key,
    ThreadKind kind,
    String carrier,
    String container,
    boolean stateInferred
) {
    public ThreadInfo {
        if (key == null || key.isBlank()) {
            key = identityKey(name, threadNumber, tid, nid);
        }
        if (kind == null) kind = ThreadKind.PLATFORM;
    }

    /** 手工构造平台线程 (测试 / 工具场景) — 以线程名作为标识。 */
    public ThreadInfo(
            String name, long threadNumber, boolean daemon, int priority, int osPriority,
            String cpuTime, String elapsed, String tid, String nid, long nidDecimal,
            ThreadState state, String stateDetail, String stackAddress,
            List<StackFrame> stackTrace, List<LockAction> lockActions, List<String> ownableSynchronizers) {
        this(name, threadNumber, daemon, priority, osPriority, cpuTime, elapsed, tid, nid, nidDecimal,
            state, stateDetail, stackAddress, stackTrace, lockActions, ownableSynchronizers,
            "name:" + name, ThreadKind.PLATFORM, null, null, false);
    }

    /**
     * 线程标识推导：Java 线程 ID (#N) 优先 — 同一 JVM 内唯一且跨 dump 稳定；
     * 无 #N 的 JVM 内部线程 (GC / VM Thread) 退化为 tid；都没有时用 名称+nid。
     */
    public static String identityKey(String name, long threadNumber, String tid, String nid) {
        if (threadNumber > 0) return "#" + threadNumber;
        if (tid != null && !tid.isBlank() && !"0x0".equals(tid)) return "tid:" + tid;
        return "name:" + name + "@" + (nid != null ? nid : "?");
    }

    public ThreadInfo withKey(String newKey) {
        return new ThreadInfo(name, threadNumber, daemon, priority, osPriority, cpuTime, elapsed, tid, nid,
            nidDecimal, state, stateDetail, stackAddress, stackTrace, lockActions, ownableSynchronizers,
            newKey, kind, carrier, container, stateInferred);
    }

    public ThreadInfo withKind(ThreadKind newKind) {
        return new ThreadInfo(name, threadNumber, daemon, priority, osPriority, cpuTime, elapsed, tid, nid,
            nidDecimal, state, stateDetail, stackAddress, stackTrace, lockActions, ownableSynchronizers,
            key, newKind, carrier, container, stateInferred);
    }

    /**
     * 生成堆栈指纹 — 用于相似堆栈聚合。
     * 将所有帧的全限定方法名拼接为一个字符串作为 hash key。
     */
    public String stackFingerprint() {
        if (stackTrace == null || stackTrace.isEmpty()) return "<empty>";
        StringBuilder sb = new StringBuilder();
        for (StackFrame f : stackTrace) {
            sb.append('|').append(f.fullMethod());
        }
        return sb.toString();
    }

    /**
     * 获取栈顶方法 (用于热点分析)
     */
    public String topMethod() {
        if (stackTrace == null || stackTrace.isEmpty()) return "<no stack>";
        return stackTrace.getFirst().fullMethod();
    }

    public boolean isVirtual() {
        return kind == ThreadKind.VIRTUAL;
    }

    /**
     * 获取该线程持有的所有锁地址
     */
    public List<String> heldLockAddresses() {
        return lockActions.stream()
            .filter(a -> a instanceof LockAction.Held)
            .map(LockAction::lockAddress)
            .toList();
    }

    /**
     * 获取该线程等待的锁地址 (如果有) — 包含所有等待类型，用于锁争用分析。
     */
    public String waitingLockAddress() {
        return lockActions.stream()
            .filter(a -> a instanceof LockAction.WaitingToLock
                      || a instanceof LockAction.ParkingToWaitFor)
            .map(LockAction::lockAddress)
            .findFirst()
            .orElse(null);
    }

    /**
     * 获取该线程因互斥锁竞争而阻塞的锁地址 — 仅用于死锁检测。
     *
     * 只返回 {@link LockAction.WaitingToLock} (synchronized 竞争)，
     * 不包含 parking/waiting on，因为：
     * - parking to wait for = LockSupport.park() / Condition.await()，线程主动释放了锁
     * - waiting on = Object.wait()，线程已释放 monitor
     * 这些不构成死锁所需的"持有并等待"条件。
     */
    public String blockingLockAddress() {
        return lockActions.stream()
            .filter(a -> a instanceof LockAction.WaitingToLock)
            .map(LockAction::lockAddress)
            .findFirst()
            .orElse(null);
    }
}
