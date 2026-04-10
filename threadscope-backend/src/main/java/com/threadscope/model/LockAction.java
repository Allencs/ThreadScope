package com.threadscope.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 锁操作 — 使用 Java 21 Sealed Interface 实现精确的代数数据类型。
 *
 * Thread Dump 中出现的四种锁操作模式：
 * - locked <addr>          → 持有锁
 * - waiting to lock <addr> → 等待获取锁 (synchronized竞争)
 * - parking to wait for <addr> → park等待 (ReentrantLock等AQS锁)
 * - waiting on <addr>      → Object.wait() 等待通知
 *
 * 每种操作都记录了 frameIndex — 该锁操作在 Thread Dump 中紧跟的堆栈帧索引。
 * 例如:
 *   at Unsafe.park(...)               ← frameIndex 0
 *   - parking to wait for <0x...>     ← 该 LockAction 的 frameIndex = 0
 *   at LockSupport.parkNanos(...)     ← frameIndex 1
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = LockAction.Held.class, name = "Held"),
    @JsonSubTypes.Type(value = LockAction.WaitingToLock.class, name = "WaitingToLock"),
    @JsonSubTypes.Type(value = LockAction.ParkingToWaitFor.class, name = "ParkingToWaitFor"),
    @JsonSubTypes.Type(value = LockAction.WaitingOn.class, name = "WaitingOn"),
})
public sealed interface LockAction {

    String lockAddress();
    String lockClassName();
    int frameIndex();

    record Held(String lockAddress, String lockClassName, int frameIndex) implements LockAction {}
    record WaitingToLock(String lockAddress, String lockClassName, int frameIndex) implements LockAction {}
    record ParkingToWaitFor(String lockAddress, String lockClassName, int frameIndex) implements LockAction {}
    record WaitingOn(String lockAddress, String lockClassName, int frameIndex) implements LockAction {}
}
