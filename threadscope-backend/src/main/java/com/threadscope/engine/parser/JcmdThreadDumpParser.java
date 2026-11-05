package com.threadscope.engine.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threadscope.exception.DumpTooLargeException;
import com.threadscope.model.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * jcmd Thread.dump_to_file 输出解析器 (JDK 21+)。
 *
 * jstack 只输出平台线程 (以及正在挂载的虚拟线程的载体)，数以万计的未挂载虚拟线程只能通过
 *   jcmd &lt;pid&gt; Thread.dump_to_file -format=json dump.json
 *   jcmd &lt;pid&gt; Thread.dump_to_file dump.txt          (纯文本)
 * 导出。两种格式都在这里解析为统一的 {@link ThreadInfo}。
 *
 * JDK 21 的导出不包含线程状态与锁信息，状态由栈顶推断 (stateInferred = true)；
 * 较新的 JDK 若输出了 state / virtual / carrier / parkBlocker / blockedOn / waitingOn / monitorsOwned 字段，按字段解析。
 */
public class JcmdThreadDumpParser {

    public enum Format { JSON, TEXT }

    public record ParsedDump(
        String jvmVersion,
        String timestamp,
        List<ThreadInfo> threads,
        boolean stateInferred,
        Format format
    ) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TEXT_THREAD_HEADER = Pattern.compile("^#(\\d+)\\s+\"(.*)\"(.*)$");
    private static final Pattern TEXT_LOCK_LINE = Pattern.compile(
        "^\\s*-\\s+(locked|waiting to lock|waiting on|parking to wait for)\\s+<([^>]+)>(?:\\s+\\(a\\s+(.+?)\\))?");
    private static final Pattern STATE_TOKEN = Pattern.compile("\\b(NEW|RUNNABLE|BLOCKED|WAITING|TIMED_WAITING|TERMINATED)\\b");

    private final int maxThreads;

    public JcmdThreadDumpParser(int maxThreads) {
        this.maxThreads = maxThreads;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  格式识别
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static Format detect(String content) {
        String head = content.length() > 8192 ? content.substring(0, 8192) : content;
        String trimmed = head.stripLeading();
        if (trimmed.startsWith("{") && head.contains("\"threadDump\"")) return Format.JSON;
        if (head.contains("Full thread dump") || head.contains("tid=0x")) return null;
        int headers = 0;
        for (String line : head.split("\n")) {
            if (TEXT_THREAD_HEADER.matcher(line.stripTrailing()).matches()) {
                if (++headers >= 1) return Format.TEXT;
            }
        }
        return null;
    }

    public ParsedDump parse(String content, Format format) {
        return format == Format.JSON ? parseJson(content) : parseText(content);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  JSON
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private record RawThread(
        long tid, String name, String container, Boolean virtualFlag, String carrierTid,
        ThreadState state, List<StackFrame> stack, List<LockAction> locks,
        String parkBlockerOwner, String parkBlockerObject
    ) {}

    private ParsedDump parseJson(String content) {
        JsonNode root;
        try {
            root = MAPPER.readTree(content);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid jcmd JSON thread dump: " + e.getMessage());
        }
        JsonNode dump = root.path("threadDump");
        String version = text(dump, "runtimeVersion");
        String time = text(dump, "time");

        List<RawThread> raws = new ArrayList<>();
        for (JsonNode container : dump.path("threadContainers")) {
            String containerName = text(container, "container");
            for (JsonNode t : container.path("threads")) {
                if (raws.size() >= maxThreads) {
                    throw new DumpTooLargeException("Dump exceeds the maximum of " + maxThreads + " threads");
                }
                raws.add(parseJsonThread(t, containerName));
            }
        }
        return buildDump(raws, version, time, Format.JSON);
    }

    private RawThread parseJsonThread(JsonNode t, String container) {
        long tid = parseLong(text(t, "tid"));
        List<StackFrame> stack = new ArrayList<>();
        for (JsonNode f : t.path("stack")) {
            StackFrame frame = parseFrame(f.asText());
            if (frame != null) stack.add(frame);
        }

        List<LockAction> locks = new ArrayList<>();
        for (JsonNode owned : t.path("monitorsOwned")) {
            int depth = owned.path("depth").asInt(0);
            for (JsonNode lock : owned.path("locks")) {
                String obj = lock.asText();
                locks.add(new LockAction.Held(obj, objectClass(obj), depth));
            }
        }
        String blockedOn = text(t, "blockedOn");
        if (blockedOn != null) locks.add(new LockAction.WaitingToLock(blockedOn, objectClass(blockedOn), 0));
        String waitingOn = text(t, "waitingOn");
        if (waitingOn != null) locks.add(new LockAction.WaitingOn(waitingOn, objectClass(waitingOn), indexOf(stack, "java.lang.Object.wait")));

        String parkObject = null;
        String parkOwner = null;
        JsonNode parkBlocker = t.path("parkBlocker");
        if (parkBlocker.isObject()) {
            parkObject = text(parkBlocker, "object");
            parkOwner = text(parkBlocker, "owner");
        } else if (parkBlocker.isTextual()) {
            parkObject = parkBlocker.asText();
        }
        if (parkObject != null) {
            locks.add(new LockAction.ParkingToWaitFor(parkObject, objectClass(parkObject), indexOf(stack, "jdk.internal.misc.Unsafe.park")));
        }
        locks.sort(Comparator.comparingInt(LockAction::frameIndex));

        Boolean virtual = t.has("virtual") ? t.path("virtual").asBoolean() : null;
        String state = text(t, "state");
        return new RawThread(tid, text(t, "name"), container, virtual, text(t, "carrier"),
            state != null ? ThreadState.fromString(state) : null, stack, locks, parkOwner, parkObject);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  纯文本
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ParsedDump parseText(String content) {
        String[] lines = content.split("\\r?\n");
        String version = null;
        String time = null;
        // 文件头三行: pid / 时间 / 运行时版本
        int headerLines = 0;
        for (String line : lines) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (TEXT_THREAD_HEADER.matcher(s).matches() || headerLines >= 3) break;
            if (headerLines == 1) time = s;
            if (headerLines == 2) version = s;
            headerLines++;
        }

        List<RawThread> raws = new ArrayList<>();
        long tid = -1;
        String name = null;
        String rest = null;
        List<StackFrame> stack = new ArrayList<>();
        List<LockAction> locks = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.stripTrailing();
            Matcher h = TEXT_THREAD_HEADER.matcher(line);
            if (h.matches()) {
                if (tid >= 0) raws.add(textThread(tid, name, rest, stack, locks));
                if (raws.size() >= maxThreads) {
                    throw new DumpTooLargeException("Dump exceeds the maximum of " + maxThreads + " threads");
                }
                tid = parseLong(h.group(1));
                name = h.group(2);
                rest = h.group(3);
                stack = new ArrayList<>();
                locks = new ArrayList<>();
                continue;
            }
            if (tid < 0 || line.isBlank()) continue;
            Matcher lock = TEXT_LOCK_LINE.matcher(line);
            if (lock.find()) {
                String obj = lock.group(2);
                String cls = lock.group(3) != null ? lock.group(3) : objectClass(obj);
                int idx = Math.max(0, stack.size() - 1);
                locks.add(switch (lock.group(1)) {
                    case "locked" -> new LockAction.Held(obj, cls, idx);
                    case "waiting to lock" -> new LockAction.WaitingToLock(obj, cls, idx);
                    case "waiting on" -> new LockAction.WaitingOn(obj, cls, idx);
                    default -> new LockAction.ParkingToWaitFor(obj, cls, idx);
                });
                continue;
            }
            StackFrame frame = parseFrame(line.trim());
            if (frame != null) stack.add(frame);
        }
        if (tid >= 0) raws.add(textThread(tid, name, rest, stack, locks));
        return buildDump(raws, version, time, Format.TEXT);
    }

    private RawThread textThread(long tid, String name, String rest, List<StackFrame> stack, List<LockAction> locks) {
        String suffix = rest != null ? rest : "";
        Matcher st = STATE_TOKEN.matcher(suffix);
        ThreadState state = st.find() ? ThreadState.fromString(st.group(1)) : null;
        Boolean virtual = suffix.contains("virtual") ? Boolean.TRUE : null;
        return new RawThread(tid, name, null, virtual, null, state, List.copyOf(stack), List.copyOf(locks), null, null);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  组装
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ParsedDump buildDump(List<RawThread> raws, String version, String time, Format format) {
        // parkBlocker.owner → 该线程持有的 JUC 锁；虚拟线程的 carrier → 载体线程
        Map<Long, List<String>> ownedSyncs = new HashMap<>();
        Map<Long, Long> carriedBy = new HashMap<>();
        for (RawThread r : raws) {
            if (r.parkBlockerOwner() != null && r.parkBlockerObject() != null) {
                ownedSyncs.computeIfAbsent(parseLong(r.parkBlockerOwner()), k -> new ArrayList<>())
                    .add(r.parkBlockerObject() + " (a " + objectClass(r.parkBlockerObject()) + ")");
            }
            if (r.carrierTid() != null) carriedBy.put(parseLong(r.carrierTid()), r.tid());
        }

        boolean inferred = false;
        List<ThreadInfo> threads = new ArrayList<>(raws.size());
        for (RawThread r : raws) {
            boolean virtual = Boolean.TRUE.equals(r.virtualFlag())
                || (r.virtualFlag() == null && hasFrame(r.stack(), "java.lang.VirtualThread.run"));
            ThreadKind kind = virtual ? ThreadKind.VIRTUAL
                : carriedBy.containsKey(r.tid()) || hasFrame(r.stack(), "jdk.internal.vm.Continuation.run")
                    ? ThreadKind.CARRIER : ThreadKind.PLATFORM;

            ThreadState state = r.state();
            boolean stateInferred = false;
            if (state == null || state == ThreadState.UNKNOWN) {
                state = inferState(r.stack());
                stateInferred = true;
                inferred = true;
            }

            String name = r.name() == null || r.name().isBlank()
                ? (virtual ? "VirtualThread[#" + r.tid() + "]" : "Thread[#" + r.tid() + "]")
                : r.name();
            String carrier = virtual
                ? (r.carrierTid() != null ? "#" + r.carrierTid() : null)
                : (carriedBy.containsKey(r.tid()) ? "#" + carriedBy.get(r.tid()) : null);

            threads.add(new ThreadInfo(
                name, r.tid(), virtual, 5, 0, null, null, null, "0x0", 0,
                state, null, null, r.stack(), r.locks(),
                List.copyOf(ownedSyncs.getOrDefault(r.tid(), List.of())),
                null, kind, carrier, r.container(), stateInferred
            ));
        }
        return new ParsedDump(version, time, threads, inferred, format);
    }

    /**
     * 由栈顶推断状态 (JDK 21 jcmd 导出不含状态)。
     */
    static ThreadState inferState(List<StackFrame> stack) {
        if (stack.isEmpty()) return ThreadState.UNKNOWN;
        int limit = Math.min(4, stack.size());
        for (int i = 0; i < limit; i++) {
            String m = stack.get(i).fullMethod();
            if (m.startsWith("java.lang.Thread.sleep") || m.endsWith("parkNanos") || m.endsWith("parkUntil")
                || m.equals("java.lang.VirtualThread.sleepNanos")) {
                return ThreadState.TIMED_WAITING;
            }
            if (m.equals("jdk.internal.misc.Unsafe.park") || m.equals("java.util.concurrent.locks.LockSupport.park")
                || m.equals("java.lang.VirtualThread.park") || m.startsWith("java.lang.Object.wait")) {
                // park(timed=true) 之上会有 parkNanos，前面已识别为 TIMED_WAITING
                continue;
            }
            break;
        }
        String top = stack.getFirst().fullMethod();
        if (top.equals("jdk.internal.misc.Unsafe.park") || top.equals("java.lang.VirtualThread.park")
            || top.startsWith("java.lang.Object.wait") || top.equals("java.lang.VirtualThread.parkOnCarrierThread")
            || top.equals("java.lang.VirtualThread.yieldContinuation")) {
            return ThreadState.WAITING;
        }
        return ThreadState.RUNNABLE;
    }

    /**
     * 解析 StackTraceElement.toString() 格式:
     *   [classLoader/][module@version/]com.acme.Foo.bar(Foo.java:12)
     */
    static StackFrame parseFrame(String s) {
        if (s == null) return null;
        String line = s.trim();
        if (line.startsWith("at ")) line = line.substring(3).trim();
        int paren = line.indexOf('(');
        if (paren <= 0 || !line.endsWith(")")) return null;
        String qualified = line.substring(0, paren);
        int slash = qualified.lastIndexOf('/');
        if (slash >= 0) qualified = qualified.substring(slash + 1);
        int dot = qualified.lastIndexOf('.');
        if (dot <= 0) return null;
        String source = line.substring(paren + 1, line.length() - 1);
        return new StackFrame(qualified.substring(0, dot), qualified.substring(dot + 1), source,
            com.threadscope.engine.pattern.DumpPatterns.parseLineNumber(source));
    }

    private static boolean hasFrame(List<StackFrame> stack, String fullMethod) {
        for (StackFrame f : stack) {
            if (f.fullMethod().equals(fullMethod)) return true;
        }
        return false;
    }

    private static int indexOf(List<StackFrame> stack, String fullMethod) {
        for (int i = 0; i < stack.size(); i++) {
            if (stack.get(i).fullMethod().equals(fullMethod)) return i;
        }
        return 0;
    }

    /** "java.lang.Object@1a2b3c" → "java.lang.Object" */
    private static String objectClass(String obj) {
        int at = obj.indexOf('@');
        return at > 0 ? obj.substring(0, at) : obj;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static long parseLong(String s) {
        if (s == null) return 0;
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }
}
