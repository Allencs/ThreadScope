package com.threadscope.engine.analyzer;

import com.threadscope.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 锁关系图构建器 — 梳理"谁持有锁，谁等待锁"的全局图谱。
 *
 * v2: 区分 BLOCKED 等待者 (WaitingToLock) 和非 BLOCKED 等待者 (ParkingToWaitFor / WaitingOn)，
 *     以便 HealthChecker 仅对真正 BLOCKED 的线程报告 BLOCK_STORM 风险。
 */
public class LockGraphBuilder {

    /**
     * 构建所有锁的持有/等待关系。
     */
    public List<LockInfo> buildLockGraph(List<ThreadInfo> threads) {
        // 1. 收集所有锁地址
        Map<String, String> lockClassNames = new HashMap<>();
        Map<String, String> lockHolders = new HashMap<>();              // lockAddr → holderThread
        Map<String, List<String>> lockWaiters = new HashMap<>();        // lockAddr → all waiting threads
        Map<String, List<String>> blockedWaiters = new HashMap<>();     // lockAddr → only BLOCKED (WaitingToLock) threads

        for (ThreadInfo thread : threads) {
            for (LockAction action : thread.lockActions()) {
                lockClassNames.putIfAbsent(action.lockAddress(), action.lockClassName());

                switch (action) {
                    case LockAction.Held held ->
                        lockHolders.put(held.lockAddress(), thread.name());

                    case LockAction.WaitingToLock waiting -> {
                        lockWaiters.computeIfAbsent(waiting.lockAddress(), k -> new ArrayList<>())
                                   .add(thread.name());
                        // WaitingToLock = synchronized 竞争 → 线程状态为 BLOCKED
                        blockedWaiters.computeIfAbsent(waiting.lockAddress(), k -> new ArrayList<>())
                                      .add(thread.name());
                    }

                    case LockAction.ParkingToWaitFor parking ->
                        lockWaiters.computeIfAbsent(parking.lockAddress(), k -> new ArrayList<>())
                                   .add(thread.name());

                    case LockAction.WaitingOn waitingOn ->
                        lockWaiters.computeIfAbsent(waitingOn.lockAddress(), k -> new ArrayList<>())
                                   .add(thread.name());
                }
            }

            // 也处理 ownable synchronizers
            for (String sync : thread.ownableSynchronizers()) {
                // 格式: "0x000000076ab220f8 (a java.util.concurrent.locks.ReentrantLock$NonfairSync)"
                int spaceIdx = sync.indexOf(' ');
                if (spaceIdx > 0) {
                    String addr = sync.substring(0, spaceIdx);
                    lockHolders.putIfAbsent(addr, thread.name());
                }
            }
        }

        // 2. 构建 LockInfo 列表 (仅返回有争用的锁)
        Set<String> allLockAddrs = new HashSet<>();
        allLockAddrs.addAll(lockHolders.keySet());
        allLockAddrs.addAll(lockWaiters.keySet());

        return allLockAddrs.stream()
            .filter(addr -> lockWaiters.containsKey(addr) && !lockWaiters.get(addr).isEmpty())
            .map(addr -> new LockInfo(
                addr,
                lockClassNames.getOrDefault(addr, "unknown"),
                lockHolders.get(addr),
                lockWaiters.getOrDefault(addr, List.of()),
                blockedWaiters.getOrDefault(addr, List.of())
            ))
            .sorted(Comparator.comparingInt(LockInfo::contentionLevel).reversed())
            .toList();
    }
}
