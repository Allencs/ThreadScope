package com.threadscope.model;

import java.util.List;

/**
 * 锁等待关系图 — 供前端以节点/边形式渲染。
 *
 * 边方向统一为 "等待者 → 锁 → 持有者"：箭头沿着"谁在等谁"流动，最终汇聚到根因线程。
 *
 * @param nodes           节点
 * @param edges           边
 * @param totalWaiters    图中涉及的等待线程总数 (含被折叠进分组的)
 * @param truncated       图过大时只保留阻塞最严重的若干连通分量
 * @param hiddenComponents 被裁掉的连通分量数
 */
public record LockGraphView(
    List<Node> nodes,
    List<Edge> edges,
    int totalWaiters,
    boolean truncated,
    int hiddenComponents
) {
    public enum NodeType { THREAD, LOCK, GROUP }

    public enum EdgeType {
        /** synchronized 竞争 (BLOCKED) */
        WAITING_TO_LOCK,
        /** LockSupport.park — ReentrantLock / Condition 等 */
        PARKING,
        /** Object.wait() 等待通知 */
        WAITING_ON,
        /** 锁 → 持有者 */
        HELD_BY
    }

    /**
     * @param id            节点 ID (线程用 "t:" + key，锁用 "l:" + 地址，分组用 "g:" + 锁地址 + 序号)
     * @param type          节点类型
     * @param label         显示文本 (线程名 / 锁类短名 / "×N 个线程")
     * @param threadKey     THREAD 节点的线程标识
     * @param state         THREAD/GROUP 节点的线程状态
     * @param topMethod     THREAD/GROUP 节点的栈顶 (或首个业务帧)
     * @param lockAddress   LOCK 节点地址
     * @param lockClassName LOCK 节点锁类名
     * @param count         GROUP 节点包含的线程数；LOCK 节点的等待者数
     * @param members       GROUP 节点的成员 (线程名 + key)，最多 200 个
     * @param root          根因线程 (持有锁、自己不等锁、拖住了其他线程)
     * @param deadlock      位于死锁环上
     * @param blockedBehind 该节点身后被拖住的线程总数 (传递闭包)
     */
    public record Node(
        String id,
        NodeType type,
        String label,
        String threadKey,
        ThreadState state,
        String topMethod,
        String lockAddress,
        String lockClassName,
        int count,
        List<Member> members,
        boolean root,
        boolean deadlock,
        int blockedBehind
    ) {}

    public record Member(String name, String key) {}

    public record Edge(String from, String to, EdgeType type, boolean deadlock) {}
}
