package com.threadscope.engine.analyzer;

import com.threadscope.model.LockAction;
import com.threadscope.model.LockGraphView;
import com.threadscope.model.ThreadInfo;
import com.threadscope.model.ThreadKind;
import com.threadscope.model.ThreadState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.threadscope.engine.analyzer.TestThreads.thread;
import static org.junit.jupiter.api.Assertions.*;

class LockGraphViewBuilderTest {

    private final LockGraphViewBuilder builder = new LockGraphViewBuilder();

    @Test
    void collapsesWaitersAndMarksRoot() {
        List<ThreadInfo> threads = new ArrayList<>();
        // root 持有 L2；middle 持有 L1 并等 L2；10 个 worker 等 L1
        threads.add(thread("root", "#1", ThreadState.RUNNABLE, null, null,
            List.of(new LockAction.Held("0x2", "com.acme.Registry", 0)), ThreadKind.PLATFORM,
            "java.net.SocketInputStream.socketRead0", "com.acme.Registry.reload"));
        threads.add(thread("middle", "#2", ThreadState.BLOCKED, null, null,
            List.of(new LockAction.WaitingToLock("0x2", "com.acme.Registry", 0),
                    new LockAction.Held("0x1", "com.acme.Cache", 1)), ThreadKind.PLATFORM,
            "com.acme.Registry.get", "com.acme.Cache.load"));
        for (int i = 0; i < 10; i++) {
            threads.add(thread("worker-" + i, "#" + (10 + i), ThreadState.BLOCKED, null, null,
                List.of(new LockAction.WaitingToLock("0x1", "com.acme.Cache", 0)), ThreadKind.PLATFORM,
                "com.acme.Cache.load", "com.acme.Api.handle"));
        }
        // 空闲池线程在队列 Condition 上 park — 默认不画
        threads.add(thread("idle", "#99", ThreadState.WAITING, null, null,
            List.of(new LockAction.ParkingToWaitFor("0x9", "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject", 1)),
            ThreadKind.PLATFORM, "jdk.internal.misc.Unsafe.park", "java.util.concurrent.LinkedBlockingQueue.take"));

        LockGraphView graph = builder.build(threads, null, false, CodeLayerClassifier.inferring());

        var group = graph.nodes().stream().filter(n -> n.type() == LockGraphView.NodeType.GROUP).toList();
        assertEquals(1, group.size(), "10 个相同等待位置的线程折叠为一个分组");
        assertEquals(10, group.getFirst().count());

        var root = graph.nodes().stream().filter(n -> "t:#1".equals(n.id())).findFirst().orElseThrow();
        assertTrue(root.root());
        assertEquals(11, root.blockedBehind(), "middle + 10 个 worker 被传递拖住");

        assertTrue(graph.nodes().stream().noneMatch(n -> "l:0x9".equals(n.id())), "无持有者的条件等待默认不画");
        assertEquals(2, graph.nodes().stream().filter(n -> n.type() == LockGraphView.NodeType.LOCK).count());
    }
}
