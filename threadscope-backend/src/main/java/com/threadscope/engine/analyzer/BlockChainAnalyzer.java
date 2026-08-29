package com.threadscope.engine.analyzer;

import com.threadscope.model.ThreadInfo;

import java.util.*;

/**
 * 锁等待链根因分析器 — 沿 "waiter → holder" 边做传递闭包，定位根阻塞线程。
 *
 * 生产事故的典型形态是传递阻塞：
 *   50 个线程 blocked 在 L1 → L1 持有者 B 又 blocked 在 L2 → L2 持有者 C 在做慢 IO。
 * 用户需要的答案是"元凶是 C"，而不是三条互不关联的锁记录。
 */
public class BlockChainAnalyzer {

    /**
     * 根阻塞线程 — 它本身没有在等 synchronized 锁，却直接或间接拖住了一批线程。
     *
     * @param rootThreadName  根阻塞线程名
     * @param directBlocked   直接阻塞的线程数
     * @param totalBlocked    传递闭包内被拖住的线程总数
     * @param maxChainDepth   最长等待链深度
     * @param blockedSamples  被拖住线程的样例
     */
    public record RootBlocker(
        String rootThreadName,
        int directBlocked,
        int totalBlocked,
        int maxChainDepth,
        List<String> blockedSamples
    ) {}

    /**
     * 找出所有根阻塞线程，按拖住的线程总数降序。
     */
    public List<RootBlocker> findRootBlockers(List<ThreadInfo> threads) {
        // lockAddr → holder
        Map<String, String> lockHolders = new HashMap<>();
        for (ThreadInfo t : threads) {
            for (String addr : t.heldLockAddresses()) {
                lockHolders.put(addr, t.name());
            }
        }

        // waiter → holder (仅 synchronized 的 waiting to lock)
        Map<String, String> waiterToHolder = new HashMap<>();
        for (ThreadInfo t : threads) {
            String waitLock = t.blockingLockAddress();
            if (waitLock == null) continue;
            String holder = lockHolders.get(waitLock);
            if (holder != null && !holder.equals(t.name())) {
                waiterToHolder.put(t.name(), holder);
            }
        }
        if (waiterToHolder.isEmpty()) return List.of();

        // 反向图: holder → 直接 waiters
        Map<String, List<String>> holderToWaiters = new HashMap<>();
        waiterToHolder.forEach((waiter, holder) ->
            holderToWaiters.computeIfAbsent(holder, k -> new ArrayList<>()).add(waiter));

        // 根 = 出现在 holder 侧、但自己不在等锁的线程 (在等锁的属于链的中间节点)
        // 注：死锁环内的线程都在等锁，不会成为根 — 死锁由 DeadlockDetector 单独报告
        List<RootBlocker> roots = new ArrayList<>();
        for (String holder : holderToWaiters.keySet()) {
            if (waiterToHolder.containsKey(holder)) continue;

            // BFS 收集传递闭包
            List<String> all = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>(holderToWaiters.get(holder));
            int depth = 0;
            while (!queue.isEmpty()) {
                depth++;
                int levelSize = queue.size();
                for (int i = 0; i < levelSize; i++) {
                    String waiter = queue.poll();
                    if (!seen.add(waiter)) continue;
                    all.add(waiter);
                    queue.addAll(holderToWaiters.getOrDefault(waiter, List.of()));
                }
            }

            roots.add(new RootBlocker(
                holder,
                holderToWaiters.get(holder).size(),
                all.size(),
                depth,
                all.stream().limit(10).toList()
            ));
        }

        roots.sort(Comparator.comparingInt(RootBlocker::totalBlocked).reversed());
        return roots;
    }
}
