package com.threadscope.engine.analyzer;

import com.threadscope.model.*;

import java.util.*;

/**
 * 锁关系图构建器 — 梳理"谁持有锁，谁等待锁"的全局图谱。
 *
 * v2: 区分 BLOCKED 等待者 (WaitingToLock) 和非 BLOCKED 等待者 (ParkingToWaitFor / WaitingOn)，
 *     以便 HealthChecker 仅对真正 BLOCKED 的线程报告 BLOCK_STORM 风险。
 */
public class LockGraphBuilder {

    private record ThreadRef(String name, String key) {}

    /**
     * 构建有争用的锁（至少有一个等待者）。无等待者的持有锁不进入竞争列表。
     */
    public List<LockInfo> buildLockGraph(List<ThreadInfo> threads) {
        return buildAll(threads).stream()
            .filter(lock -> lock.contentionLevel() > 0)
            .toList();
    }

    /**
     * 按地址查找一把锁，包括没有等待者的持有锁。
     * jcmd 的 monitorsOwned 只有持有者、没有 waiter，点开详情时仍需要能定位到持有线程。
     */
    public LockInfo lookup(List<ThreadInfo> threads, String address) {
        if (address == null || address.isBlank()) return null;
        String needle = address.trim();
        return buildAll(threads).stream()
            .filter(lock -> lock.lockAddress().equals(needle))
            .findFirst()
            .orElse(null);
    }

    private List<LockInfo> buildAll(List<ThreadInfo> threads) {
        Map<String, String> lockClassNames = new HashMap<>();
        Map<String, ThreadRef> lockHolders = new HashMap<>();
        Map<String, List<ThreadRef>> lockWaiters = new HashMap<>();
        Map<String, List<ThreadRef>> blockedWaiters = new HashMap<>();
        Map<String, Set<String>> waitingOnPerLock = new HashMap<>();

        for (ThreadInfo thread : threads) {
            ThreadRef ref = new ThreadRef(thread.name(), thread.key());
            for (LockAction action : thread.lockActions()) {
                lockClassNames.putIfAbsent(action.lockAddress(), action.lockClassName());

                switch (action) {
                    case LockAction.Held held ->
                        lockHolders.putIfAbsent(held.lockAddress(), ref);

                    case LockAction.WaitingToLock waiting -> {
                        lockWaiters.computeIfAbsent(waiting.lockAddress(), k -> new ArrayList<>()).add(ref);
                        blockedWaiters.computeIfAbsent(waiting.lockAddress(), k -> new ArrayList<>()).add(ref);
                    }

                    case LockAction.ParkingToWaitFor parking ->
                        lockWaiters.computeIfAbsent(parking.lockAddress(), k -> new ArrayList<>()).add(ref);

                    case LockAction.WaitingOn waitingOn -> {
                        lockWaiters.computeIfAbsent(waitingOn.lockAddress(), k -> new ArrayList<>()).add(ref);
                        waitingOnPerLock.computeIfAbsent(waitingOn.lockAddress(), k -> new HashSet<>()).add(thread.key());
                    }
                }
            }

            for (String sync : thread.ownableSynchronizers()) {
                int spaceIdx = sync.indexOf(' ');
                if (spaceIdx > 0) {
                    String addr = sync.substring(0, spaceIdx);
                    lockHolders.putIfAbsent(addr, ref);
                    lockClassNames.putIfAbsent(addr, syncClassName(sync.substring(spaceIdx + 1)));
                }
            }
        }

        // Object.wait() 会释放 monitor：持有者同时在 wait 同一把锁时，不再算持有者。
        for (var it = lockHolders.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            String addr = entry.getKey();
            ThreadRef holder = entry.getValue();
            if (waitingOnPerLock.getOrDefault(addr, Set.of()).contains(holder.key())) {
                it.remove();
                List<ThreadRef> waiters = lockWaiters.get(addr);
                if (waiters != null) waiters.removeIf(w -> w.key().equals(holder.key()));
            }
        }

        Set<String> allLockAddrs = new HashSet<>();
        allLockAddrs.addAll(lockHolders.keySet());
        allLockAddrs.addAll(lockWaiters.keySet());

        return allLockAddrs.stream()
            .map(addr -> {
                ThreadRef holder = lockHolders.get(addr);
                List<ThreadRef> waiters = lockWaiters.getOrDefault(addr, List.of());
                List<ThreadRef> blocked = blockedWaiters.getOrDefault(addr, List.of());
                return new LockInfo(
                    addr,
                    lockClassNames.getOrDefault(addr, "unknown"),
                    holder != null ? holder.name() : null,
                    waiters.stream().map(ThreadRef::name).toList(),
                    blocked.stream().map(ThreadRef::name).toList(),
                    holder != null ? holder.key() : null
                );
            })
            .sorted(Comparator.comparingInt(LockInfo::contentionLevel).reversed())
            .toList();
    }

    /** "(a java.util.concurrent.locks.ReentrantLock$NonfairSync)" → 类名 */
    private static String syncClassName(String raw) {
        String s = raw.trim();
        if (s.startsWith("(a ")) s = s.substring(3);
        if (s.endsWith(")")) s = s.substring(0, s.length() - 1);
        return s.isBlank() ? "unknown" : s;
    }
}
