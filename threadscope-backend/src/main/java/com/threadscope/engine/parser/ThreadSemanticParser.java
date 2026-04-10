package com.threadscope.engine.parser;

import com.threadscope.engine.pattern.DumpPatterns;
import com.threadscope.model.*;

import java.util.*;
import java.util.regex.Matcher;

/**
 * 单个线程块的语义解析器。
 *
 * 将 {@link ThreadDumpLexer.RawThreadBlock} 解析为完整的 {@link ThreadInfo} 领域对象。
 * 该解析器是无状态的、线程安全的，可以被 Virtual Threads 并行调用。
 */
public class ThreadSemanticParser {

    /**
     * 解析一个原始线程块为完整的 ThreadInfo。
     */
    public ThreadInfo parse(ThreadDumpLexer.RawThreadBlock block) {
        // 1. 解析头部行
        var headerInfo = parseHeader(block.headerLine());

        // 2. 解析状态行
        ThreadState state = ThreadState.UNKNOWN;
        String stateDetail = null;
        if (block.stateLine() != null) {
            Matcher stateMatcher = DumpPatterns.THREAD_STATE.matcher(block.stateLine());
            if (stateMatcher.find()) {
                state = ThreadState.fromString(stateMatcher.group(1));
                stateDetail = stateMatcher.group(2); // 可能为 null
            }
        }

        // 如果状态行没有解析出来，尝试从头部行的状态描述推断
        if (state == ThreadState.UNKNOWN && headerInfo.stateDesc() != null) {
            state = inferStateFromDescription(headerInfo.stateDesc());
        }

        // 3. 解析堆栈帧和锁操作
        List<StackFrame> stackFrames = new ArrayList<>();
        List<LockAction> lockActions = new ArrayList<>();
        parseBodyLines(block.bodyLines(), stackFrames, lockActions);

        // 4. 解析可拥有同步器
        List<String> ownableSynchronizers = parseOwnableSynchronizers(block.ownableSyncLines());

        return new ThreadInfo(
            headerInfo.name(),
            headerInfo.threadNumber(),
            headerInfo.daemon(),
            headerInfo.priority(),
            headerInfo.osPriority(),
            headerInfo.cpuTime(),
            headerInfo.elapsed(),
            headerInfo.tid(),
            headerInfo.nid(),
            headerInfo.nidDecimal(),
            state,
            stateDetail,
            headerInfo.stackAddress(),
            List.copyOf(stackFrames),
            List.copyOf(lockActions),
            List.copyOf(ownableSynchronizers)
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  头部解析
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private record HeaderInfo(
        String name, long threadNumber, boolean daemon,
        int priority, int osPriority,
        String cpuTime, String elapsed,
        String tid, String nid, long nidDecimal,
        String stateDesc, String stackAddress
    ) {}

    private HeaderInfo parseHeader(String headerLine) {
        Matcher m = DumpPatterns.THREAD_HEADER.matcher(headerLine);
        if (m.matches()) {
            String nidRaw = m.group(10);                              // nid: "0x1a3" or "62"
            long nidDecimal = parseNid(nidRaw);
            String nidDisplay = nidRaw.startsWith("0x") ? nidRaw : "0x" + Long.toHexString(nidDecimal);

            // group(3) is the bracket nid [62] from JDK 24+, can also use as nidDecimal if present
            if (m.group(3) != null) {
                nidDecimal = parseLongSafe(m.group(3));
            }

            return new HeaderInfo(
                m.group(1),                                          // name
                parseLongSafe(m.group(2)),                           // threadNumber
                m.group(4) != null,                                  // daemon (was group 3)
                parseIntSafe(m.group(5)),                            // priority (was group 4)
                parseIntSafe(m.group(6)),                            // osPriority (was group 5)
                m.group(7),                                          // cpuTime (was group 6)
                m.group(8),                                          // elapsed (was group 7)
                m.group(9),                                          // tid (was group 8)
                nidDisplay,                                          // nid (normalized to hex)
                nidDecimal,                                          // nidDecimal
                m.group(11),                                         // stateDesc (was group 10)
                m.group(12)                                          // stackAddress (was group 11)
            );
        }

        // 尝试简化格式
        Matcher sm = DumpPatterns.THREAD_HEADER_SIMPLE.matcher(headerLine);
        if (sm.find()) {
            String nidRaw = sm.group(2);
            long nidDecimal = parseNid(nidRaw);
            String nidDisplay = nidRaw.startsWith("0x") ? nidRaw : "0x" + Long.toHexString(nidDecimal);
            return new HeaderInfo(
                sm.group(1), 0, false, 5, 0,
                null, null, "0x0", nidDisplay, nidDecimal,
                sm.group(3), null
            );
        }

        // 最基本的名字提取
        String name = headerLine;
        if (headerLine.startsWith("\"") && headerLine.contains("\"")) {
            name = headerLine.substring(1, headerLine.indexOf('"', 1));
        }
        return new HeaderInfo(name, 0, false, 5, 0, null, null, "0x0", "0x0", 0, null, null);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  堆栈体解析
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void parseBodyLines(List<String> lines, List<StackFrame> frames, List<LockAction> locks) {
        for (String line : lines) {
            // 尝试匹配堆栈帧
            Matcher frameMatcher = DumpPatterns.STACK_FRAME.matcher(line);
            if (frameMatcher.find()) {
                String className = frameMatcher.group(1);
                String methodName = frameMatcher.group(2);
                String source = frameMatcher.group(3);
                int lineNumber = DumpPatterns.parseLineNumber(source);
                frames.add(new StackFrame(className, methodName, source, lineNumber));
                continue;
            }

            // 锁操作行出现在某个堆栈帧之后，frameIndex 指向最近一个帧
            int currentFrameIdx = frames.size() - 1;
            if (currentFrameIdx < 0) currentFrameIdx = 0; // 安全兜底

            Matcher lockHeld = DumpPatterns.LOCK_HELD.matcher(line);
            if (lockHeld.find()) {
                locks.add(new LockAction.Held(lockHeld.group(1), lockHeld.group(2), currentFrameIdx));
                continue;
            }

            Matcher lockWaiting = DumpPatterns.LOCK_WAITING.matcher(line);
            if (lockWaiting.find()) {
                locks.add(new LockAction.WaitingToLock(lockWaiting.group(1), lockWaiting.group(2), currentFrameIdx));
                continue;
            }

            Matcher lockParking = DumpPatterns.LOCK_PARKING.matcher(line);
            if (lockParking.find()) {
                locks.add(new LockAction.ParkingToWaitFor(lockParking.group(1), lockParking.group(2), currentFrameIdx));
                continue;
            }

            Matcher lockWaitingOn = DumpPatterns.LOCK_WAITING_ON.matcher(line);
            if (lockWaitingOn.find()) {
                locks.add(new LockAction.WaitingOn(lockWaitingOn.group(1), lockWaitingOn.group(2), currentFrameIdx));
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Ownable Synchronizers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private List<String> parseOwnableSynchronizers(List<String> lines) {
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            Matcher m = DumpPatterns.OWNABLE_SYNC_ENTRY.matcher(line);
            if (m.find()) {
                result.add(m.group(1) + " (" + m.group(2) + ")");
            }
        }
        return result;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  辅助方法
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ThreadState inferStateFromDescription(String desc) {
        if (desc == null) return ThreadState.UNKNOWN;
        String lower = desc.toLowerCase();
        if (lower.contains("runnable")) return ThreadState.RUNNABLE;
        if (lower.contains("blocked")) return ThreadState.BLOCKED;
        if (lower.contains("waiting on condition")) return ThreadState.TIMED_WAITING;
        if (lower.contains("in object.wait")) return ThreadState.WAITING;
        if (lower.contains("sleeping")) return ThreadState.TIMED_WAITING;
        return ThreadState.UNKNOWN;
    }

    private static long parseHexToLong(String hex) {
        if (hex == null) return 0;
        try {
            return Long.parseLong(hex.replaceFirst("0[xX]", ""), 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 解析 nid — 自动识别十六进制 (0x...) 或十进制格式。
     */
    private static long parseNid(String nid) {
        if (nid == null) return 0;
        if (nid.startsWith("0x") || nid.startsWith("0X")) {
            return parseHexToLong(nid);
        }
        return parseLongSafe(nid);
    }

    private static int parseIntSafe(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static long parseLongSafe(String s) {
        if (s == null) return 0;
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }
}
