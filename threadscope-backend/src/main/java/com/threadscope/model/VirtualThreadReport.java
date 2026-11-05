package com.threadscope.model;

import java.util.List;
import java.util.Map;

/**
 * 虚拟线程分析报告。
 *
 * @param virtualCount        dump 中可见的虚拟线程数 (jstack 只能看到已挂载的，jcmd 才能看到全部)
 * @param carrierCount        载体线程数 (虚拟线程调度器 ForkJoinPool 的 worker)
 * @param mountedCount        正在承载虚拟线程的载体线程数
 * @param pinnedCount         被钉住 (pinned) 的线程数
 * @param jvmMajorVersion     JVM 主版本 (无法识别时为 0)；JDK 24+ synchronized 不再导致 pinning (JEP 491)
 * @param visibility          FULL = 来自 jcmd 导出，能看到全部虚拟线程；MOUNTED_ONLY = 来自 jstack，只能看到挂载中的
 * @param virtualStates       虚拟线程状态分布
 * @param pinned              被钉住的线程明细
 * @param containers          按线程容器统计的虚拟线程数量
 */
public record VirtualThreadReport(
    int virtualCount,
    int carrierCount,
    int mountedCount,
    int pinnedCount,
    int jvmMajorVersion,
    String visibility,
    Map<ThreadState, Long> virtualStates,
    List<PinnedThread> pinned,
    List<ContainerStat> containers
) {
    public boolean hasVirtualThreads() {
        return virtualCount > 0 || mountedCount > 0;
    }

    /**
     * @param threadName  线程名
     * @param threadKey   线程标识
     * @param kind        VIRTUAL / CARRIER
     * @param reason      被钉住的原因
     * @param topFrames   栈顶若干帧
     * @param blockingFrame 导致钉住的关键帧 (synchronized 所在方法 / native 方法)
     */
    public record PinnedThread(
        String threadName,
        String threadKey,
        ThreadKind kind,
        String reason,
        List<String> topFrames,
        String blockingFrame
    ) {}

    public record ContainerStat(String container, int count, Map<ThreadState, Long> stateDistribution) {}
}
