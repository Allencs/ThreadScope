package com.threadscope.engine.analyzer;

import com.threadscope.model.*;
import com.threadscope.model.LockGraphView.*;

import java.util.*;

/**
 * 锁等待关系图构建器 — 把线程与锁之间的持有/等待关系整理成可渲染的节点和边。
 *
 * 可读性处理：
 * 1. 同一把锁下处于相同等待位置的"叶子等待者"(自己不持有被等待的锁) 折叠为一个分组节点，
 *    200 个线程卡在同一处时只画一个 "×200" 节点；
 * 2. 默认不画无持有者的条件等待 (空闲线程池在队列 Condition 上 park)，那是常态而非竞争；
 * 3. 图过大时按连通分量的阻塞规模排序，只保留最严重的部分。
 */
public class LockGraphViewBuilder {

    private static final int GROUP_MIN = 3;
    private static final int MAX_SINGLE_WAITERS = 8;
    private static final int MAX_NODES = 240;
    private static final int MAX_MEMBERS = 200;

    private record Wait(String waiterKey, String lockAddr, EdgeType type) {}

    public LockGraphView build(
            List<ThreadInfo> threads,
            DeadlockInfo deadlocks,
            boolean includeConditionWaits,
            CodeLayerClassifier classifier) {

        Map<String, ThreadInfo> byKey = new LinkedHashMap<>();
        for (ThreadInfo t : threads) byKey.putIfAbsent(t.key(), t);

        // ── 1. 锁持有者 / 锁类名 ──
        Map<String, String> lockClass = new HashMap<>();
        Map<String, String> holders = new HashMap<>();
        for (ThreadInfo t : threads) {
            Set<String> waitingOnSelf = new HashSet<>();
            for (LockAction a : t.lockActions()) {
                lockClass.putIfAbsent(a.lockAddress(), a.lockClassName());
                if (a instanceof LockAction.WaitingOn) waitingOnSelf.add(a.lockAddress());
            }
            for (LockAction a : t.lockActions()) {
                // Object.wait() 会释放 monitor — 同一对象上 wait 的线程不是持有者
                if (a instanceof LockAction.Held && !waitingOnSelf.contains(a.lockAddress())) {
                    holders.putIfAbsent(a.lockAddress(), t.key());
                }
            }
            for (String sync : t.ownableSynchronizers()) {
                int space = sync.indexOf(' ');
                String addr = space > 0 ? sync.substring(0, space) : sync;
                holders.putIfAbsent(addr, t.key());
                if (space > 0 && !lockClass.containsKey(addr)) {
                    lockClass.put(addr, sync.substring(space + 1).replaceAll("^\\(a |\\)$", ""));
                }
            }
        }

        // ── 2. 每个线程的主等待 (离栈顶最近的那个) ──
        List<Wait> waits = new ArrayList<>();
        for (ThreadInfo t : threads) {
            Wait w = primaryWait(t);
            if (w == null) continue;
            String holder = holders.get(w.lockAddr());
            if (t.key().equals(holder)) continue;
            boolean contention = holder != null || w.type() == EdgeType.WAITING_TO_LOCK;
            if (!includeConditionWaits && (w.type() == EdgeType.WAITING_ON || !contention)) continue;
            waits.add(w);
        }

        Map<String, List<Wait>> waitsByLock = new LinkedHashMap<>();
        for (Wait w : waits) waitsByLock.computeIfAbsent(w.lockAddr(), k -> new ArrayList<>()).add(w);

        // 线程级等待图: waiter → holder，用于根因与传递闭包
        Map<String, String> waiterToHolder = new HashMap<>();
        for (Wait w : waits) {
            String holder = holders.get(w.lockAddr());
            if (holder != null) waiterToHolder.put(w.waiterKey(), holder);
        }
        Set<String> heldIncluded = new HashSet<>();
        for (String addr : waitsByLock.keySet()) {
            String h = holders.get(addr);
            if (h != null) heldIncluded.add(h);
        }

        // ── 3. 死锁标记 ──
        Set<String> deadlockNames = new HashSet<>();
        Set<String> deadlockLocks = new HashSet<>();
        if (deadlocks != null && deadlocks.chains() != null) {
            for (var chain : deadlocks.chains()) {
                deadlockNames.addAll(chain.threadNames());
                deadlockLocks.addAll(chain.lockAddresses());
            }
        }

        Map<String, Integer> behind = transitiveBlocked(waiterToHolder);

        // ── 4. 节点与边 ──
        Map<String, Node> nodes = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();
        int totalWaiters = 0;

        for (var entry : waitsByLock.entrySet()) {
            String addr = entry.getKey();
            List<Wait> lockWaits = entry.getValue();
            totalWaiters += lockWaits.size();
            String lockId = "l:" + addr;
            String cls = lockClass.getOrDefault(addr, "unknown");
            boolean lockInDeadlock = deadlockLocks.contains(addr);
            nodes.put(lockId, new Node(lockId, NodeType.LOCK, shortClass(cls), null, null, null,
                addr, cls, lockWaits.size(), List.of(), false, lockInDeadlock, 0));

            String holderKey = holders.get(addr);
            if (holderKey != null && byKey.containsKey(holderKey)) {
                ThreadInfo h = byKey.get(holderKey);
                String hid = addThreadNode(nodes, h, classifier, deadlockNames, behind, waiterToHolder);
                edges.add(new Edge(lockId, hid, EdgeType.HELD_BY,
                    lockInDeadlock && deadlockNames.contains(h.name())));
            }

            // 叶子等待者 (自己不持有被等待的锁) 才参与折叠
            List<Wait> leaves = new ArrayList<>();
            for (Wait w : lockWaits) {
                if (heldIncluded.contains(w.waiterKey())) {
                    ThreadInfo t = byKey.get(w.waiterKey());
                    String tid = addThreadNode(nodes, t, classifier, deadlockNames, behind, waiterToHolder);
                    edges.add(new Edge(tid, lockId, w.type(), lockInDeadlock && deadlockNames.contains(t.name())));
                } else {
                    leaves.add(w);
                }
            }

            Map<String, List<Wait>> byLocation = new LinkedHashMap<>();
            for (Wait w : leaves) {
                ThreadInfo t = byKey.get(w.waiterKey());
                String loc = w.type() + "|" + t.state() + "|" + location(t, classifier);
                byLocation.computeIfAbsent(loc, k -> new ArrayList<>()).add(w);
            }
            List<Wait> singles = new ArrayList<>();
            int groupSeq = 0;
            for (List<Wait> g : byLocation.values()) {
                if (g.size() >= GROUP_MIN) {
                    addGroup(nodes, edges, lockId, addr, groupSeq++, g, byKey, classifier);
                } else {
                    singles.addAll(g);
                }
            }
            if (singles.size() > MAX_SINGLE_WAITERS) {
                addGroup(nodes, edges, lockId, addr, groupSeq, singles.subList(MAX_SINGLE_WAITERS - 1, singles.size()),
                    byKey, classifier);
                singles = singles.subList(0, MAX_SINGLE_WAITERS - 1);
            }
            for (Wait w : singles) {
                ThreadInfo t = byKey.get(w.waiterKey());
                String tid = addThreadNode(nodes, t, classifier, deadlockNames, behind, waiterToHolder);
                edges.add(new Edge(tid, lockId, w.type(), false));
            }
        }

        // ── 5. 连通分量裁剪 ──
        List<List<String>> components = components(nodes.keySet(), edges);
        components.sort(Comparator.comparingInt((List<String> c) -> componentScore(c, nodes)).reversed());
        Set<String> keep = new HashSet<>();
        int hidden = 0;
        for (List<String> c : components) {
            if (!keep.isEmpty() && keep.size() + c.size() > MAX_NODES) {
                hidden++;
                continue;
            }
            keep.addAll(c);
        }

        List<Node> keptNodes = nodes.values().stream().filter(n -> keep.contains(n.id())).toList();
        List<Edge> keptEdges = edges.stream().filter(e -> keep.contains(e.from()) && keep.contains(e.to())).toList();
        return new LockGraphView(keptNodes, keptEdges, totalWaiters, hidden > 0, hidden);
    }

    private static Wait primaryWait(ThreadInfo t) {
        for (LockAction a : t.lockActions()) {
            switch (a) {
                case LockAction.WaitingToLock w -> { return new Wait(t.key(), w.lockAddress(), EdgeType.WAITING_TO_LOCK); }
                case LockAction.ParkingToWaitFor p -> { return new Wait(t.key(), p.lockAddress(), EdgeType.PARKING); }
                case LockAction.WaitingOn w -> { return new Wait(t.key(), w.lockAddress(), EdgeType.WAITING_ON); }
                default -> { }
            }
        }
        return null;
    }

    private String addThreadNode(Map<String, Node> nodes, ThreadInfo t, CodeLayerClassifier classifier,
                                 Set<String> deadlockNames, Map<String, Integer> behind,
                                 Map<String, String> waiterToHolder) {
        String id = "t:" + t.key();
        if (!nodes.containsKey(id)) {
            int blocked = behind.getOrDefault(t.key(), 0);
            boolean root = blocked > 0 && !waiterToHolder.containsKey(t.key());
            nodes.put(id, new Node(id, NodeType.THREAD, t.name(), t.key(), t.state(), location(t, classifier),
                null, null, 1, List.of(), root, deadlockNames.contains(t.name()), blocked));
        }
        return id;
    }

    private void addGroup(Map<String, Node> nodes, List<Edge> edges, String lockId, String addr, int seq,
                          List<Wait> group, Map<String, ThreadInfo> byKey, CodeLayerClassifier classifier) {
        ThreadInfo sample = byKey.get(group.getFirst().waiterKey());
        String gid = "g:" + addr + ":" + seq;
        List<Member> members = group.stream()
            .limit(MAX_MEMBERS)
            .map(w -> byKey.get(w.waiterKey()))
            .map(t -> new Member(t.name(), t.key()))
            .toList();
        nodes.put(gid, new Node(gid, NodeType.GROUP, "×" + group.size() + " 个线程", null, sample.state(),
            location(sample, classifier), null, null, group.size(), members, false, false, 0));
        edges.add(new Edge(gid, lockId, group.getFirst().type(), false));
    }

    /** 每个持有者身后被拖住的线程数 (传递闭包) */
    private static Map<String, Integer> transitiveBlocked(Map<String, String> waiterToHolder) {
        Map<String, List<String>> reverse = new HashMap<>();
        waiterToHolder.forEach((w, h) -> reverse.computeIfAbsent(h, k -> new ArrayList<>()).add(w));
        Map<String, Integer> result = new HashMap<>();
        for (String holder : reverse.keySet()) {
            Set<String> seen = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>(reverse.get(holder));
            while (!queue.isEmpty()) {
                String w = queue.poll();
                if (w.equals(holder) || !seen.add(w)) continue;
                queue.addAll(reverse.getOrDefault(w, List.of()));
            }
            result.put(holder, seen.size());
        }
        return result;
    }

    private static List<List<String>> components(Collection<String> ids, List<Edge> edges) {
        Map<String, List<String>> adj = new HashMap<>();
        for (String id : ids) adj.put(id, new ArrayList<>());
        for (Edge e : edges) {
            adj.get(e.from()).add(e.to());
            adj.get(e.to()).add(e.from());
        }
        Set<String> seen = new HashSet<>();
        List<List<String>> result = new ArrayList<>();
        for (String id : ids) {
            if (!seen.add(id)) continue;
            List<String> comp = new ArrayList<>();
            Deque<String> stack = new ArrayDeque<>(List.of(id));
            while (!stack.isEmpty()) {
                String cur = stack.pop();
                comp.add(cur);
                for (String n : adj.get(cur)) {
                    if (seen.add(n)) stack.push(n);
                }
            }
            result.add(comp);
        }
        return result;
    }

    private static int componentScore(List<String> comp, Map<String, Node> nodes) {
        int score = 0;
        for (String id : comp) {
            Node n = nodes.get(id);
            if (n.deadlock()) score += 100_000;
            if (n.type() == NodeType.GROUP) score += n.count();
            if (n.type() == NodeType.THREAD) score += 1;
        }
        return score;
    }

    private static String location(ThreadInfo t, CodeLayerClassifier classifier) {
        if (t.stackTrace().isEmpty()) return null;
        StackFrame business = classifier.firstBusinessFrame(t);
        return business != null ? business.fullMethod() : t.topMethod();
    }

    private static String shortClass(String cls) {
        int dot = cls.lastIndexOf('.');
        return dot >= 0 ? cls.substring(dot + 1) : cls;
    }
}
