package com.threadscope.model;

import java.util.List;

/**
 * 单个线程的完整信息 — 解析后的核心数据结构。
 *
 * @param name               线程名称
 * @param threadNumber       线程编号 (#42)
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
    List<String> ownableSynchronizers
) {
    /**
     * 生成堆栈指纹 — 用于相似堆栈聚合。
     * 将所有帧的全限定方法名拼接为一个字符串作为 hash key。
     */
    public String stackFingerprint() {
        if (stackTrace == null || stackTrace.isEmpty()) return "<empty>";
        return stackTrace.stream()
            .map(StackFrame::fullMethod)
            .reduce("", (a, b) -> a + "|" + b);
    }

    /**
     * 获取栈顶方法 (用于热点分析)
     */
    public String topMethod() {
        if (stackTrace == null || stackTrace.isEmpty()) return "<no stack>";
        return stackTrace.getFirst().fullMethod();
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
