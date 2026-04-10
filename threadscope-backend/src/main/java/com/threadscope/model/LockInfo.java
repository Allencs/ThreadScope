package com.threadscope.model;

import java.util.List;

/**
 * 锁详情信息 — 描述一把锁的持有者与等待者。
 *
 * waitingThreadNames: 所有等待该锁的线程 (包括 WaitingToLock、ParkingToWaitFor、WaitingOn)
 * blockedWaiterNames: 仅因 synchronized 竞争 (WaitingToLock) 而处于 BLOCKED 状态的线程
 */
public record LockInfo(
    String lockAddress,
    String lockClassName,
    String holderThreadName,
    List<String> waitingThreadNames,
    List<String> blockedWaiterNames
) {
    /**
     * 兼容旧构造 — 向后兼容不区分 blocked/non-blocked 的场景。
     */
    public LockInfo(String lockAddress, String lockClassName, String holderThreadName, List<String> waitingThreadNames) {
        this(lockAddress, lockClassName, holderThreadName, waitingThreadNames, List.of());
    }

    /** 所有等待者数量 */
    public int contentionLevel() {
        return waitingThreadNames != null ? waitingThreadNames.size() : 0;
    }

    /** 真正 BLOCKED 的等待者数量 (仅 WaitingToLock / synchronized 竞争) */
    public int blockedWaiterCount() {
        return blockedWaiterNames != null ? blockedWaiterNames.size() : 0;
    }
}
