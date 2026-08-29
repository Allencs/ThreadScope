package com.threadscope.engine.analyzer;

import com.threadscope.model.*;

import java.util.*;

/**
 * 死锁检测器 — 基于有向图环检测算法。
 *
 * 原理：构建"线程-锁"有向图，使用 DFS 检测环。
 *   Thread A --持有--> Lock X
 *   Thread A --等待--> Lock Y
 *   Thread B --持有--> Lock Y
 *   Thread B --等待--> Lock X
 *   → 形成环: A → Y → B → X → A (死锁)
 */
public class DeadlockDetector {

    /**
     * 检测死锁。
     *
     * @param threads         所有线程信息
     * @param deadlockSection JVM自报告的死锁段文本 (优先使用)
     * @return 死锁信息
     */
    public DeadlockInfo detect(List<ThreadInfo> threads, List<String> deadlockSection) {
        List<DeadlockInfo.DeadlockChain> chains = new ArrayList<>();

        // 策略1: 使用 JVM 自报告的死锁信息 (最准确)
        if (deadlockSection != null && !deadlockSection.isEmpty()) {
            chains.addAll(parseJvmDeadlockSection(deadlockSection));
        }

        // 策略2: 自主图分析检测 (补充JVM未报告的潜在死锁)
        chains.addAll(detectByGraphAnalysis(threads));

        // 去重
        chains = deduplicateChains(chains);

        return new DeadlockInfo(chains);
    }

    /**
     * 策略2: 基于图的死锁检测。
     * 构建 线程→锁→线程 的有向图，使用 DFS 检测环。
     *
     * 仅基于 synchronized 互斥锁 (WaitingToLock) 构图，
     * 排除 parking/waiting on 等条件等待，避免产生大量误报。
     */
    private List<DeadlockInfo.DeadlockChain> detectByGraphAnalysis(List<ThreadInfo> threads) {
        // 构建锁持有映射: lockAddress → holderThreadName
        Map<String, String> lockHolders = new HashMap<>();
        for (ThreadInfo thread : threads) {
            for (String lockAddr : thread.heldLockAddresses()) {
                lockHolders.put(lockAddr, thread.name());
            }
        }

        // 构建等待关系: threadName → waitingForLockAddress
        // 仅使用 blockingLockAddress() — 只有 "waiting to lock" 才参与死锁检测
        Map<String, String> threadWaiting = new HashMap<>();
        for (ThreadInfo thread : threads) {
            String waitLock = thread.blockingLockAddress();
            if (waitLock != null) {
                threadWaiting.put(thread.name(), waitLock);
            }
        }

        // 三色 DFS 检测环：
        //   白 = 未访问；灰 = 在当前路径上 (indexInPath)；黑 = 已探索完毕 (done)。
        // 每个线程至多等待一把锁 (函数式图)，因此从每个白色节点沿唯一出边前进即可：
        //   - 撞到灰色节点 → 找到环
        //   - 撞到黑色节点 → 该方向已探索过，不会有新环
        // 只有确认无环后才把路径标黑，避免"过早标记 visited"导致的漏检。
        List<DeadlockInfo.DeadlockChain> chains = new ArrayList<>();
        Set<String> done = new HashSet<>();

        for (String startThread : threadWaiting.keySet()) {
            if (done.contains(startThread)) continue;

            List<String> path = new ArrayList<>();
            Map<String, Integer> indexInPath = new HashMap<>();
            String current = startThread;
            String cycleEntry = null;

            while (current != null) {
                if (indexInPath.containsKey(current)) {
                    cycleEntry = current;               // 灰色节点 → 环
                    break;
                }
                if (done.contains(current)) break;      // 黑色节点 → 无新环

                indexInPath.put(current, path.size());
                path.add(current);

                String waitLock = threadWaiting.get(current);
                if (waitLock == null) break;            // 当前线程不在等待任何锁
                current = lockHolders.get(waitLock);    // 锁的持有者 (可能为 null → 链断开)
            }

            done.addAll(path);

            if (cycleEntry != null) {
                List<String> cycle = List.copyOf(path.subList(indexInPath.get(cycleEntry), path.size()));

                if (cycle.size() >= 2) {
                    List<String> cycleLocks = cycle.stream()
                        .map(threadWaiting::get)
                        .filter(Objects::nonNull)
                        .toList();

                    String desc = "Deadlock detected: " + String.join(" → ", cycle) + " → " + cycleEntry;
                    chains.add(new DeadlockInfo.DeadlockChain(cycle, cycleLocks, desc));
                }
            }
        }

        return chains;
    }

    /**
     * 解析 JVM 自报告的死锁段。
     * dump 中可能有多段 "Found one Java-level deadlock:"，按段头切分为独立的死锁链，
     * 避免把多个死锁合并成一条。
     */
    private List<DeadlockInfo.DeadlockChain> parseJvmDeadlockSection(List<String> lines) {
        List<DeadlockInfo.DeadlockChain> chains = new ArrayList<>();
        List<String> currentThreads = new ArrayList<>();
        List<String> currentLocks = new ArrayList<>();

        for (String line : lines) {
            // 新的死锁段开始 → 先保存上一条链
            if (com.threadscope.engine.pattern.DumpPatterns.DEADLOCK_HEADER.matcher(line).find()) {
                flushJvmChain(chains, currentThreads, currentLocks);
                currentThreads = new ArrayList<>();
                currentLocks = new ArrayList<>();
                continue;
            }

            var threadRef = com.threadscope.engine.pattern.DumpPatterns.DEADLOCK_THREAD_REF.matcher(line);
            if (threadRef.find()) {
                // 同一线程在段内出现多次 (堆栈重复段) 时去重
                if (!currentThreads.contains(threadRef.group(1))) {
                    currentThreads.add(threadRef.group(1));
                }
            }

            var lockRef = com.threadscope.engine.pattern.DumpPatterns.DEADLOCK_WAITING_LOCK.matcher(line);
            if (lockRef.find()) {
                currentLocks.add(lockRef.group(2));
            }
        }

        flushJvmChain(chains, currentThreads, currentLocks);
        return chains;
    }

    private void flushJvmChain(
            List<DeadlockInfo.DeadlockChain> chains,
            List<String> threads,
            List<String> locks) {
        if (!threads.isEmpty()) {
            chains.add(new DeadlockInfo.DeadlockChain(
                List.copyOf(threads), List.copyOf(locks),
                "JVM reported deadlock involving: " + String.join(", ", threads)
            ));
        }
    }

    private List<DeadlockInfo.DeadlockChain> deduplicateChains(List<DeadlockInfo.DeadlockChain> chains) {
        Set<String> seen = new HashSet<>();
        return chains.stream()
            .filter(chain -> {
                String key = new TreeSet<>(chain.threadNames()).toString();
                return seen.add(key);
            })
            .toList();
    }
}
