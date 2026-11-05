package com.threadscope.engine.analyzer;

import com.threadscope.model.*;
import com.threadscope.model.VirtualThreadReport.ContainerStat;
import com.threadscope.model.VirtualThreadReport.PinnedThread;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 虚拟线程分析器 — 统计虚拟线程/载体线程，并识别 pinning (虚拟线程钉住载体线程)。
 *
 * pinning 的两种 dump 特征：
 * 1. 栈中出现 VirtualThread.parkOnCarrierThread — 虚拟线程想 park 却无法卸载，只能连同载体一起阻塞
 *    (原因：持有 synchronized 监视器 [JDK 21-23]、栈上有 native 帧、类初始化中)
 * 2. 虚拟线程 (或正在承载虚拟线程的载体) 处于 BLOCKED 等待 synchronized — JDK 21-23 的 monitor enter
 *    无法卸载虚拟线程，同样占住载体
 * 载体池默认只有 CPU 核数个线程，少量 pinning 就足以让所有虚拟线程停摆。
 */
public class VirtualThreadAnalyzer {

    private static final String PARK_ON_CARRIER = "java.lang.VirtualThread.parkOnCarrierThread";
    private static final Set<String> CONTINUATION_FRAMES = Set.of(
        "jdk.internal.vm.Continuation.run",
        "jdk.internal.vm.Continuation.enter",
        "jdk.internal.vm.Continuation.enter0",
        "jdk.internal.vm.Continuation.enterSpecial",
        "java.lang.VirtualThread.runContinuation"
    );
    private static final Pattern FJ_WORKER = Pattern.compile("^(ForkJoinPool-\\d+)-worker-\\d+$");
    private static final int TOP_FRAMES = 8;

    /**
     * @param fullVisibility 输入是否来自 jcmd 导出 (能看到全部虚拟线程)
     */
    public VirtualThreadReport analyze(List<ThreadInfo> threads, int jvmMajorVersion, boolean fullVisibility) {
        List<ThreadInfo> virtuals = threads.stream().filter(ThreadInfo::isVirtual).toList();

        // 载体池 = 有线程在跑 Continuation 的 ForkJoinPool-N
        Set<String> schedulerPools = new HashSet<>();
        for (ThreadInfo t : threads) {
            if (t.isVirtual()) continue;
            Matcher m = FJ_WORKER.matcher(t.name());
            if (m.matches() && (t.kind() == ThreadKind.CARRIER || runsContinuation(t))) {
                schedulerPools.add(m.group(1));
            }
        }
        int carrierCount = 0;
        int mountedCount = 0;
        for (ThreadInfo t : threads) {
            if (t.isVirtual()) continue;
            Matcher m = FJ_WORKER.matcher(t.name());
            boolean inScheduler = m.matches() && schedulerPools.contains(m.group(1));
            boolean mounted = t.kind() == ThreadKind.CARRIER || runsContinuation(t);
            if (inScheduler || mounted) carrierCount++;
            if (mounted) mountedCount++;
        }

        List<PinnedThread> pinned = new ArrayList<>();
        for (ThreadInfo t : threads) {
            boolean vthreadContext = t.isVirtual() || t.kind() == ThreadKind.CARRIER || runsContinuation(t);
            if (!vthreadContext) continue;
            PinnedThread p = detectPinning(t, jvmMajorVersion);
            if (p != null) pinned.add(p);
        }

        Map<ThreadState, Long> virtualStates = virtuals.stream()
            .collect(Collectors.groupingBy(ThreadInfo::state, () -> new EnumMap<>(ThreadState.class), Collectors.counting()));

        List<ContainerStat> containers = virtuals.stream()
            .collect(Collectors.groupingBy(t -> t.container() != null ? t.container() : "<unknown>",
                LinkedHashMap::new, Collectors.toList()))
            .entrySet().stream()
            .map(e -> new ContainerStat(e.getKey(), e.getValue().size(),
                e.getValue().stream().collect(Collectors.groupingBy(ThreadInfo::state,
                    () -> new EnumMap<>(ThreadState.class), Collectors.counting()))))
            .sorted(Comparator.comparingInt(ContainerStat::count).reversed())
            .limit(20)
            .toList();

        return new VirtualThreadReport(
            virtuals.size(),
            carrierCount,
            mountedCount,
            pinned.size(),
            jvmMajorVersion,
            fullVisibility ? "FULL" : "MOUNTED_ONLY",
            virtualStates,
            pinned,
            containers
        );
    }

    /** 栈中是否有 Continuation 帧 — 说明该平台线程正在承载虚拟线程 */
    public static boolean runsContinuation(ThreadInfo t) {
        for (StackFrame f : t.stackTrace()) {
            if (CONTINUATION_FRAMES.contains(FrameClassifier.normalize(f.fullMethod()))) return true;
        }
        return false;
    }

    private PinnedThread detectPinning(ThreadInfo t, int jvmMajor) {
        List<StackFrame> stack = t.stackTrace();
        int parkIdx = -1;
        for (int i = 0; i < stack.size(); i++) {
            if (FrameClassifier.normalize(stack.get(i).fullMethod()).startsWith(PARK_ON_CARRIER)) {
                parkIdx = i;
                break;
            }
        }
        boolean monitorPinnable = jvmMajor == 0 || jvmMajor < 24;

        if (parkIdx >= 0) {
            final int parkAt = parkIdx;
            Optional<LockAction> held = t.lockActions().stream()
                .filter(a -> a instanceof LockAction.Held && a.frameIndex() > parkAt)
                .findFirst();
            StackFrame nativeFrame = null;
            for (int i = parkIdx + 1; i < stack.size(); i++) {
                StackFrame f = stack.get(i);
                if (f.isNative() && !CONTINUATION_FRAMES.contains(FrameClassifier.normalize(f.fullMethod()))) {
                    nativeFrame = f;
                    break;
                }
            }
            String reason;
            String blocking;
            if (held.isPresent() && monitorPinnable) {
                reason = "持有 synchronized 监视器时阻塞 (park)，虚拟线程无法卸载，连同载体线程一起被挂起";
                blocking = frameAt(stack, held.get().frameIndex());
            } else if (nativeFrame != null) {
                reason = "调用栈中存在 native 帧，阻塞时虚拟线程无法卸载";
                blocking = nativeFrame.fullMethod();
            } else if (monitorPinnable) {
                reason = "阻塞时无法卸载 — 调用栈中可能存在 synchronized 块 (dump 未记录监视器) 或 native 帧";
                blocking = firstNonJdkFrame(stack, parkIdx + 1);
            } else {
                reason = "阻塞时无法卸载 — 调用栈中存在 native 帧或正在执行类初始化 (<clinit>)";
                blocking = firstNonJdkFrame(stack, parkIdx + 1);
            }
            return new PinnedThread(t.name(), t.key(), t.kind(), reason, topFrames(stack), blocking);
        }

        if (monitorPinnable && t.state() == ThreadState.BLOCKED && t.blockingLockAddress() != null) {
            String blocking = t.lockActions().stream()
                .filter(a -> a instanceof LockAction.WaitingToLock)
                .findFirst()
                .map(a -> frameAt(stack, a.frameIndex()))
                .orElse(firstNonJdkFrame(stack, 0));
            return new PinnedThread(t.name(), t.key(), t.kind(),
                "等待进入 synchronized 监视器 — JDK 21~23 中 monitor enter 无法卸载虚拟线程，载体线程被占用",
                topFrames(stack), blocking);
        }
        return null;
    }

    private static String frameAt(List<StackFrame> stack, int idx) {
        return idx >= 0 && idx < stack.size() ? stack.get(idx).fullMethod() : null;
    }

    private static String firstNonJdkFrame(List<StackFrame> stack, int from) {
        for (int i = Math.max(0, from); i < stack.size(); i++) {
            if (!stack.get(i).isJdkFrame()) return stack.get(i).fullMethod();
        }
        return null;
    }

    private static List<String> topFrames(List<StackFrame> stack) {
        return stack.stream().limit(TOP_FRAMES).map(StackFrame::fullMethod).toList();
    }
}
