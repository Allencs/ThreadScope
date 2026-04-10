package com.threadscope.engine.analyzer;

import com.threadscope.model.*;

import java.util.*;
import java.util.stream.Collectors;

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

        // DFS 检测环
        List<DeadlockInfo.DeadlockChain> chains = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        for (String startThread : threadWaiting.keySet()) {
            if (visited.contains(startThread)) continue;

            List<String> path = new ArrayList<>();
            Set<String> pathSet = new HashSet<>();
            String current = startThread;
            boolean foundCycle = false;

            while (current != null) {
                // 如果 current 已在当前路径中 → 检测到环
                if (pathSet.contains(current)) {
                    foundCycle = true;
                    break;
                }

                path.add(current);
                pathSet.add(current);
                visited.add(current);

                // current 线程等待的锁 → 该锁的持有者
                String waitLock = threadWaiting.get(current);
                if (waitLock == null) break;     // 当前线程不在等待任何锁 → 不可能构成死锁
                String holder = lockHolders.get(waitLock);
                if (holder == null) break;       // 没找到持有者 → 链断开

                // 持有者本身也必须在等待某个锁，才可能构成死锁环
                // 如果持有者没有 WaitingToLock，则链到此为止，不是死锁
                if (!threadWaiting.containsKey(holder)) break;

                current = holder;
            }

            // 仅在真正检测到环时才报告（至少2个线程参与）
            if (foundCycle) {
                int cycleStart = path.indexOf(current);
                List<String> cycle = new ArrayList<>(path.subList(cycleStart, path.size()));

                if (cycle.size() >= 2) {
                    List<String> cycleLocks = cycle.stream()
                        .map(threadWaiting::get)
                        .filter(Objects::nonNull)
                        .toList();

                    String desc = "Deadlock detected: " + String.join(" → ", cycle) + " → " + current;
                    chains.add(new DeadlockInfo.DeadlockChain(cycle, cycleLocks, desc));
                }
            }
        }

        return chains;
    }

    /**
     * 解析 JVM 自报告的死锁段。
     */
    private List<DeadlockInfo.DeadlockChain> parseJvmDeadlockSection(List<String> lines) {
        List<DeadlockInfo.DeadlockChain> chains = new ArrayList<>();
        List<String> currentThreads = new ArrayList<>();
        List<String> currentLocks = new ArrayList<>();

        for (String line : lines) {
            var threadRef = com.threadscope.engine.pattern.DumpPatterns.DEADLOCK_THREAD_REF.matcher(line);
            if (threadRef.find()) {
                currentThreads.add(threadRef.group(1));
            }

            var lockRef = com.threadscope.engine.pattern.DumpPatterns.DEADLOCK_WAITING_LOCK.matcher(line);
            if (lockRef.find()) {
                currentLocks.add(lockRef.group(2));
            }
        }

        if (!currentThreads.isEmpty()) {
            chains.add(new DeadlockInfo.DeadlockChain(
                currentThreads, currentLocks,
                "JVM reported deadlock involving: " + String.join(", ", currentThreads)
            ));
        }

        return chains;
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
