package com.threadscope.jfr;

import com.threadscope.config.JfrProperties;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次扫描 .jfr。热点只统计 jdk.ExecutionSample；同一条样本里同一个方法只计一次。
 * 调用者取栈上更靠近入口的下一帧。
 */
public class JfrRecordingParser {

    private static final int STACK_LIMIT = 32;
    private static final int SERIES_LIMIT = 2000;
    /** jdk.CPULoad 默认每秒一条，足够覆盖 12 小时的录制。 */
    private static final int CPU_LOAD_LIMIT = 43_200;

    private final JfrProperties properties;

    public JfrRecordingParser(JfrProperties properties) {
        this.properties = properties;
    }

    public JfrDocuments.ParseResult parse(Path file) {
        Instant[] range = scanRange(file);
        Instant min = range[0];
        Instant max = range[1];
        int bucketCount = bucketCount(min, max);

        Map<String, Acc> methods = new HashMap<>();
        StackTable stacks = new StackTable();
        List<String> dumps = new ArrayList<>();
        List<Long> dumpTimes = new ArrayList<>();
        Map<Long, PlatformAcc> platform = new HashMap<>();
        List<JfrDocuments.StatPoint> stats = new ArrayList<>();
        Map<Long, VirtualAcc> virtual = new LinkedHashMap<>();
        long virtualEnded = 0;
        long pinned = 0;
        String jvmVersion = null;
        long samples = 0;
        List<JfrDocuments.CpuLoadPoint> cpuLoad = new ArrayList<>();
        Integer hwThreads = null;
        Integer cores = null;
        String virtualization = null;
        JfrDocuments.ContainerInfo container = null;
        List<JfrDocuments.ThrottlePoint> throttling = new ArrayList<>();
        JfrMemoryCollector memory = new JfrMemoryCollector(min, max, bucketCount);

        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String name = event.getEventType().getName();
                memory.accept(event, name);
                switch (name) {
                    case "jdk.ExecutionSample" -> samples += onSample(event, methods, stacks, min, max, bucketCount);
                    case "jdk.ThreadDump" -> {
                        if (has(event, "result")) {
                            String text = event.getString("result");
                            if (text != null && !text.isBlank()) {
                                dumps.add(text);
                                dumpTimes.add(event.getStartTime().toEpochMilli());
                            }
                        }
                    }
                    case "jdk.JVMInformation" -> {
                        if (jvmVersion == null && has(event, "jvmVersion")) {
                            jvmVersion = event.getString("jvmVersion");
                        }
                    }
                    case "jdk.ThreadStart" -> onStart(event, platform);
                    case "jdk.ThreadEnd" -> onEnd(event, platform);
                    case "jdk.JavaThreadStatistics" -> stats.add(new JfrDocuments.StatPoint(
                        event.getStartTime().toEpochMilli(),
                        longOr(event, "activeCount", 0),
                        longOr(event, "daemonCount", 0),
                        longOr(event, "accumulatedCount", 0),
                        longOr(event, "peakCount", 0)
                    ));
                    case "jdk.ThreadCPULoad" -> onCpu(event, platform);
                    case "jdk.CPULoad" -> {
                        if (cpuLoad.size() < CPU_LOAD_LIMIT) cpuLoad.add(new JfrDocuments.CpuLoadPoint(
                            event.getStartTime().toEpochMilli(),
                            floatOr(event, "jvmUser"), floatOr(event, "jvmSystem"), floatOr(event, "machineTotal")));
                    }
                    case "jdk.CPUInformation" -> {
                        if (hwThreads == null && has(event, "hwThreads")) hwThreads = event.getInt("hwThreads");
                        if (cores == null && has(event, "cores")) cores = event.getInt("cores");
                    }
                    case "jdk.VirtualizationInformation" -> {
                        if (virtualization == null && has(event, "name")) virtualization = event.getString("name");
                    }
                    case "jdk.ContainerConfiguration" -> {
                        if (container == null) container = container(event);
                    }
                    case "jdk.ContainerCPUThrottling" -> {
                        if (throttling.size() < CPU_LOAD_LIMIT && has(event, "cpuElapsedSlices")) {
                            throttling.add(new JfrDocuments.ThrottlePoint(
                                event.getStartTime().toEpochMilli(),
                                event.getLong("cpuElapsedSlices"),
                                longOr(event, "cpuThrottledSlices", 0),
                                nanosOr(event, "cpuThrottledTime")));
                        }
                    }
                    case "jdk.ThreadAllocationStatistics" -> onAlloc(event, platform);
                    case "jdk.VirtualThreadStart" -> onVirtual(event, virtual, false);
                    case "jdk.VirtualThreadEnd" -> {
                        onVirtual(event, virtual, true);
                        virtualEnded++;
                    }
                    case "jdk.VirtualThreadPinned" -> {
                        pinned++;
                        onPinned(event, virtual);
                    }
                    default -> { }
                }
            }
        } catch (IOException e) {
            throw new JfrParseException("无法解析 JFR 文件: " + e.getMessage(), e);
        }

        long[] bucketSamples = new long[bucketCount];
        Map<String, JfrDocuments.MethodStat> frozen = new LinkedHashMap<>();
        for (var entry : methods.entrySet()) {
            Acc acc = entry.getValue();
            for (int i = 0; i < bucketCount; i++) bucketSamples[i] += acc.selfBuckets[i];
            frozen.put(entry.getKey(), acc.freeze());
        }
        sortDumps(dumps, dumpTimes);

        List<JfrDocuments.PlatformThread> platforms = platform.values().stream()
            .map(PlatformAcc::freeze)
            .sorted(Comparator.comparing((JfrDocuments.PlatformThread t) -> t.name() == null ? "" : t.name())
                .thenComparingLong(JfrDocuments.PlatformThread::javaThreadId))
            .toList();
        boolean aggregate = virtual.size() > properties.virtualThreadDetailLimit();
        var threads = new JfrDocuments.ThreadDocument(
            platforms, List.copyOf(stats), freezeVirtual(virtual, virtualEnded, pinned, aggregate));
        var hotspot = new JfrDocuments.HotspotDocument(
            samples, min.toEpochMilli(), max.toEpochMilli(), bucketCount, toList(bucketSamples), Map.copyOf(frozen),
            stacks.frames(), stacks.freeze(), new JfrDocuments.CpuDocument(hwThreads, cores, sortCpu(cpuLoad),
                virtualization, container, sortThrottling(throttling)));
        var summary = new JfrDocuments.Summary(jvmVersion, platforms.size(), virtual.size(), pinned);
        return new JfrDocuments.ParseResult(hotspot, threads, summary, List.copyOf(dumps),
            max.toEpochMilli() - min.toEpochMilli(), memory.freeze(container == null ? null : container.memoryLimit()));
    }

    private Instant[] scanRange(Path file) {
        Instant sampleMin = null;
        Instant sampleMax = null;
        Instant anyMin = null;
        Instant anyMax = null;
        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                Instant time = event.getStartTime();
                if (anyMin == null || time.isBefore(anyMin)) anyMin = time;
                if (anyMax == null || time.isAfter(anyMax)) anyMax = time;
                String name = event.getEventType().getName();
                if ("jdk.ExecutionSample".equals(name) || JfrMemoryCollector.isAllocation(name)) {
                    if (sampleMin == null || time.isBefore(sampleMin)) sampleMin = time;
                    if (sampleMax == null || time.isAfter(sampleMax)) sampleMax = time;
                }
            }
        } catch (IOException e) {
            throw new JfrParseException("无法解析 JFR 文件: " + e.getMessage(), e);
        }
        if (anyMin == null) throw new JfrParseException("录制文件里没有任何事件");
        Instant min = sampleMin != null ? sampleMin : anyMin;
        Instant max = sampleMax != null ? sampleMax : anyMax;
        return new Instant[] {min, max};
    }

    private int bucketCount(Instant min, Instant max) {
        long span = max.toEpochMilli() - min.toEpochMilli();
        if (span <= 0) return 1;
        int configured = Math.max(1, properties.historyBuckets());
        long seconds = Math.max(1, span / 1000);
        return (int) Math.min(configured, seconds);
    }

    static int bucketOf(Instant time, Instant min, Instant max, int bucketCount) {
        if (bucketCount <= 1) return 0;
        long span = Math.max(1, max.toEpochMilli() - min.toEpochMilli());
        int index = (int) ((time.toEpochMilli() - min.toEpochMilli()) * bucketCount / span);
        if (index < 0) return 0;
        return Math.min(index, bucketCount - 1);
    }

    private int onSample(RecordedEvent event, Map<String, Acc> methods, StackTable stacks,
                         Instant min, Instant max, int bucketCount) {
        if (!has(event, "stackTrace")) return 0;
        RecordedStackTrace stack = event.getStackTrace();
        if (stack == null || stack.getFrames().isEmpty()) return 0;
        List<String> chain = new ArrayList<>();
        for (RecordedFrame frame : stack.getFrames()) {
            if (hidden(frame)) continue;
            String key = methodKey(frame.getMethod());
            if (key == null) continue;
            if (!chain.isEmpty() && chain.getLast().equals(key)) continue;
            chain.add(key);
        }
        if (chain.isEmpty()) return 0;
        int bucket = bucketOf(event.getStartTime(), min, max, bucketCount);
        stacks.add(chain, stack.isTruncated(), bucket);
        var seen = new HashSet<String>();
        for (int i = 0; i < chain.size(); i++) {
            String key = chain.get(i);
            Acc acc = methods.computeIfAbsent(key, k -> new Acc(bucketCount));
            if (i == 0) {
                acc.self++;
                acc.selfBuckets[bucket]++;
            }
            if (!seen.add(key)) continue;
            acc.inclusive++;
            acc.inclusiveBuckets[bucket]++;
            if (i + 1 < chain.size()) acc.callers.merge(chain.get(i + 1), 1L, Long::sum);
        }
        return 1;
    }

    private void onStart(RecordedEvent event, Map<Long, PlatformAcc> platform) {
        RecordedThread thread = thread(event, "thread");
        if (thread == null) thread = thread(event, "eventThread");
        if (thread == null) return;
        PlatformAcc acc = platform.computeIfAbsent(thread.getJavaThreadId(), PlatformAcc::new);
        acc.apply(thread);
        acc.existedBefore = false;
        acc.startEpochMs = event.getStartTime().toEpochMilli();
        acc.startStack = stackLines(event);
        RecordedThread parent = thread(event, "parentThread");
        if (parent != null) {
            acc.parentJavaThreadId = parent.getJavaThreadId();
            acc.parentName = parent.getJavaName();
        }
    }

    private void onEnd(RecordedEvent event, Map<Long, PlatformAcc> platform) {
        RecordedThread thread = thread(event, "thread");
        if (thread == null) thread = thread(event, "eventThread");
        if (thread == null) return;
        PlatformAcc acc = platform.computeIfAbsent(thread.getJavaThreadId(), PlatformAcc::new);
        acc.apply(thread);
        acc.endEpochMs = event.getStartTime().toEpochMilli();
        acc.aliveAtEnd = false;
    }

    private void onCpu(RecordedEvent event, Map<Long, PlatformAcc> platform) {
        RecordedThread thread = thread(event, "eventThread");
        if (thread == null) return;
        PlatformAcc acc = platform.computeIfAbsent(thread.getJavaThreadId(), PlatformAcc::new);
        acc.apply(thread);
        if (acc.cpu.size() >= SERIES_LIMIT) return;
        acc.cpu.add(new JfrDocuments.CpuPoint(
            event.getStartTime().toEpochMilli(),
            has(event, "user") ? event.getFloat("user") : 0f,
            has(event, "system") ? event.getFloat("system") : 0f));
    }

    private void onAlloc(RecordedEvent event, Map<Long, PlatformAcc> platform) {
        RecordedThread thread = thread(event, "thread");
        if (thread == null) return;
        PlatformAcc acc = platform.computeIfAbsent(thread.getJavaThreadId(), PlatformAcc::new);
        acc.apply(thread);
        if (acc.allocation.size() >= SERIES_LIMIT) return;
        acc.allocation.add(new JfrDocuments.AllocPoint(
            event.getStartTime().toEpochMilli(), longOr(event, "allocated", 0)));
    }

    private void onVirtual(RecordedEvent event, Map<Long, VirtualAcc> virtual, boolean end) {
        if (!has(event, "javaThreadId")) return;
        long id = event.getLong("javaThreadId");
        VirtualAcc acc = virtual.computeIfAbsent(id, k -> new VirtualAcc());
        long time = event.getStartTime().toEpochMilli();
        if (end) {
            acc.endEpochMs = time;
            return;
        }
        acc.startEpochMs = time;
        if (acc.stack.isEmpty()) acc.stack = stackLines(event);
        if (has(event, "duration") && event.getDuration() != null && !event.getDuration().isZero()) {
            acc.endEpochMs = time + event.getDuration().toMillis();
        }
    }

    private void onPinned(RecordedEvent event, Map<Long, VirtualAcc> virtual) {
        RecordedThread thread = thread(event, "eventThread");
        if (thread == null) return;
        VirtualAcc acc = virtual.computeIfAbsent(thread.getJavaThreadId(), k -> new VirtualAcc());
        acc.pinned++;
        if (acc.stack.isEmpty()) acc.stack = stackLines(event);
    }

    private static JfrDocuments.VirtualSummary freezeVirtual(
            Map<Long, VirtualAcc> virtual, long ended, long pinned, boolean aggregate) {
        if (!aggregate) {
            List<JfrDocuments.VirtualThread> threads = virtual.entrySet().stream()
                .map(e -> new JfrDocuments.VirtualThread(
                    e.getKey(), e.getValue().startEpochMs, e.getValue().endEpochMs,
                    e.getValue().stack, e.getValue().pinned))
                .toList();
            return new JfrDocuments.VirtualSummary(virtual.size(), ended, pinned, false, List.of(), threads);
        }
        Map<String, long[]> groups = new LinkedHashMap<>();
        for (VirtualAcc acc : virtual.values()) {
            String key = String.join("\n", acc.stack);
            long[] count = groups.computeIfAbsent(key, k -> new long[2]);
            count[0]++;
            count[1] += acc.pinned;
        }
        List<JfrDocuments.VirtualGroup> list = groups.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
            .limit(50)
            .map(e -> new JfrDocuments.VirtualGroup(
                e.getKey().isEmpty() ? List.of() : List.of(e.getKey().split("\n")),
                e.getValue()[0], e.getValue()[1]))
            .toList();
        return new JfrDocuments.VirtualSummary(virtual.size(), ended, pinned, true, list, List.of());
    }

    private static void sortDumps(List<String> dumps, List<Long> times) {
        record Pair(long time, String text) {}
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < dumps.size(); i++) pairs.add(new Pair(times.get(i), dumps.get(i)));
        pairs.sort(Comparator.comparingLong(Pair::time));
        dumps.clear();
        for (Pair pair : pairs) dumps.add(pair.text());
    }

    private static List<Long> toList(long[] values) {
        List<Long> list = new ArrayList<>(values.length);
        for (long value : values) list.add(value);
        return list;
    }

    /** 方法键带参数列表（与 jfr print 的写法一致），重载方法分开统计。 */
    static String methodKey(RecordedMethod method) {
        String name = methodName(method);
        return name == null ? null : name + params(method.getDescriptor());
    }

    private static String methodName(RecordedMethod method) {
        if (method == null || method.getType() == null || method.getName() == null) return null;
        return method.getType().getName() + "#" + method.getName();
    }

    /** 方法句柄、lambda form 等 @Hidden 帧；jfr print / jfr view 和 Java 异常栈默认都不显示。 */
    static boolean hidden(RecordedFrame frame) {
        RecordedMethod method = frame.getMethod();
        return method != null && method.isHidden();
    }

    /** "(Ljava/lang/Object;[I)V" → "(Object, int[])"。 */
    static String params(String descriptor) {
        if (descriptor == null || descriptor.isEmpty() || descriptor.charAt(0) != '(') return "()";
        StringBuilder out = new StringBuilder("(");
        int i = 1;
        boolean first = true;
        while (i < descriptor.length() && descriptor.charAt(i) != ')') {
            int dims = 0;
            while (descriptor.charAt(i) == '[') {
                dims++;
                i++;
            }
            char c = descriptor.charAt(i);
            String name;
            if (c == 'L') {
                int end = descriptor.indexOf(';', i);
                if (end < 0) break;
                String type = descriptor.substring(i + 1, end);
                name = type.substring(type.lastIndexOf('/') + 1);
                i = end + 1;
            } else {
                name = switch (c) {
                    case 'B' -> "byte";
                    case 'C' -> "char";
                    case 'D' -> "double";
                    case 'F' -> "float";
                    case 'I' -> "int";
                    case 'J' -> "long";
                    case 'S' -> "short";
                    case 'Z' -> "boolean";
                    default -> "?";
                };
                i++;
            }
            if (!first) out.append(", ");
            first = false;
            out.append(name).append("[]".repeat(dims));
        }
        return out.append(')').toString();
    }

    private static List<JfrDocuments.CpuLoadPoint> sortCpu(List<JfrDocuments.CpuLoadPoint> points) {
        points.sort(Comparator.comparingLong(JfrDocuments.CpuLoadPoint::epochMs));
        return List.copyOf(points);
    }

    private static List<JfrDocuments.ThrottlePoint> sortThrottling(List<JfrDocuments.ThrottlePoint> points) {
        points.sort(Comparator.comparingLong(JfrDocuments.ThrottlePoint::epochMs));
        return List.copyOf(points);
    }

    private static JfrDocuments.ContainerInfo container(RecordedEvent event) {
        Double quotaCores = null;
        if (has(event, "cpuQuota") && has(event, "cpuSlicePeriod")) {
            Duration quota = event.getDuration("cpuQuota");
            Duration period = event.getDuration("cpuSlicePeriod");
            if (!quota.isNegative() && !quota.isZero() && !period.isNegative() && !period.isZero()) {
                quotaCores = (double) quota.toNanos() / period.toNanos();
            }
        }
        return new JfrDocuments.ContainerInfo(
            has(event, "containerType") ? event.getString("containerType") : null,
            quotaCores,
            positive(event, "effectiveCpuCount"),
            positive(event, "cpuShares"),
            positive(event, "memoryLimit"),
            positive(event, "hostTotalMemory"));
    }

    /** cgroup 里 -1 / 0 表示未设置。 */
    private static Long positive(RecordedEvent event, String field) {
        if (!has(event, field)) return null;
        long value = event.getLong(field);
        return value > 0 && value != Long.MAX_VALUE ? value : null;
    }

    static long nanosOr(RecordedEvent event, String field) {
        if (!has(event, field)) return 0;
        Duration value = event.getDuration(field);
        return value.isNegative() ? 0 : value.toNanos();
    }

    private static float floatOr(RecordedEvent event, String field) {
        return has(event, field) ? event.getFloat(field) : 0f;
    }

    static String label(String methodKey) {
        int hash = methodKey.indexOf('#');
        String className = hash < 0 ? methodKey : methodKey.substring(0, hash);
        String method = hash < 0 ? "" : methodKey.substring(hash + 1);
        int dot = className.lastIndexOf('.');
        int slash = className.lastIndexOf('/');
        int cut = Math.max(dot, slash);
        String simple = cut < 0 ? className : className.substring(cut + 1);
        return method.isEmpty() ? simple : simple + "." + method;
    }

    private static List<String> stackLines(RecordedEvent event) {
        if (!has(event, "stackTrace")) return List.of();
        RecordedStackTrace stack = event.getStackTrace();
        if (stack == null) return List.of();
        List<String> lines = new ArrayList<>();
        for (RecordedFrame frame : stack.getFrames()) {
            if (hidden(frame)) continue;
            String key = methodName(frame.getMethod());
            if (key == null) continue;
            int line = frame.getLineNumber();
            lines.add(key.replace('#', '.') + (line > 0 ? ":" + line : ""));
            if (lines.size() == STACK_LIMIT) break;
        }
        return List.copyOf(lines);
    }

    private static RecordedThread thread(RecordedEvent event, String field) {
        if (!has(event, field)) return null;
        return event.getThread(field);
    }

    static boolean has(RecordedEvent event, String field) {
        return event.getEventType().getField(field) != null;
    }

    static long longOr(RecordedEvent event, String field, long fallback) {
        return has(event, field) ? event.getLong(field) : fallback;
    }

    private static final class Acc {
        long self;
        long inclusive;
        final Map<String, Long> callers = new HashMap<>();
        final long[] selfBuckets;
        final long[] inclusiveBuckets;

        Acc(int buckets) {
            this.selfBuckets = new long[buckets];
            this.inclusiveBuckets = new long[buckets];
        }

        JfrDocuments.MethodStat freeze() {
            return new JfrDocuments.MethodStat(
                self, inclusive, Map.copyOf(callers), sparse(selfBuckets), sparse(inclusiveBuckets));
        }

        private static Map<String, Long> sparse(long[] values) {
            Map<String, Long> map = new LinkedHashMap<>();
            for (int i = 0; i < values.length; i++) {
                if (values[i] > 0) map.put(Integer.toString(i), values[i]);
            }
            return map;
        }
    }

    /** 方法键 → 下标。CPU 栈和各种分配栈可以共用一张表。 */
    static final class FrameNames {
        private final Map<String, Integer> ids = new HashMap<>();
        private final List<String> names = new ArrayList<>();

        int id(String key) {
            return ids.computeIfAbsent(key, k -> {
                names.add(k);
                return names.size() - 1;
            });
        }

        List<String> list() {
            return List.copyOf(names);
        }
    }

    /**
     * 方法名转成下标后按整条栈去重累计，同时记录每条栈落在哪些时间桶，用于按时间区间统计。
     * CPU 栈每个样本权重为 1；分配栈的权重是估算字节数，并且同一条栈按对象类型分开记。
     */
    static final class StackTable {
        private final FrameNames names;
        private final Map<StackKey, StackCount> counts = new LinkedHashMap<>();

        StackTable() {
            this(new FrameNames());
        }

        StackTable(FrameNames names) {
            this.names = names;
        }

        void add(List<String> chain, boolean truncated, int bucket) {
            int[] frames = new int[chain.size()];
            for (int i = 0; i < frames.length; i++) frames[i] = names.id(chain.get(i));
            add(frames, truncated, bucket, 1, -1);
        }

        /** type < 0 表示不区分类型。 */
        void add(int[] frames, boolean truncated, int bucket, long weight, int type) {
            counts.computeIfAbsent(new StackKey(frames, truncated, type), k -> new StackCount()).add(bucket, weight);
        }

        List<String> frames() {
            return names.list();
        }

        List<JfrDocuments.StackSample> freeze() {
            List<JfrDocuments.StackSample> list = new ArrayList<>(counts.size());
            counts.forEach((key, count) -> list.add(count.freeze(key)));
            list.sort(Comparator.comparingLong(JfrDocuments.StackSample::samples).reversed());
            return list;
        }

        /** 大多数栈只出现在少数几个桶里，用有序的稀疏数组记录。 */
        private static final class StackCount {
            long total;
            int[] buckets = new int[2];
            long[] samples = new long[2];
            int size;

            void add(int bucket, long weight) {
                total += weight;
                if (size > 0 && buckets[size - 1] == bucket) {
                    samples[size - 1] += weight;
                    return;
                }
                int at = Arrays.binarySearch(buckets, 0, size, bucket);
                if (at >= 0) {
                    samples[at] += weight;
                    return;
                }
                at = -at - 1;
                if (size == buckets.length) {
                    buckets = Arrays.copyOf(buckets, size * 2);
                    samples = Arrays.copyOf(samples, size * 2);
                }
                System.arraycopy(buckets, at, buckets, at + 1, size - at);
                System.arraycopy(samples, at, samples, at + 1, size - at);
                buckets[at] = bucket;
                samples[at] = weight;
                size++;
            }

            JfrDocuments.StackSample freeze(StackKey key) {
                return new JfrDocuments.StackSample(key.frames, total, key.truncated,
                    Arrays.copyOf(buckets, size), Arrays.copyOf(samples, size), key.type < 0 ? null : key.type);
            }
        }

        private record StackKey(int[] frames, boolean truncated, int type) {
            @Override
            public boolean equals(Object o) {
                return o instanceof StackKey other && truncated == other.truncated && type == other.type
                    && Arrays.equals(frames, other.frames);
            }

            @Override
            public int hashCode() {
                return 31 * (31 * Arrays.hashCode(frames) + (truncated ? 1 : 0)) + type;
            }
        }
    }

    private static final class PlatformAcc {
        final long javaThreadId;
        String name;
        Long osThreadId;
        Long parentJavaThreadId;
        String parentName;
        Long startEpochMs;
        Long endEpochMs;
        boolean existedBefore = true;
        boolean aliveAtEnd = true;
        List<String> startStack = List.of();
        final List<JfrDocuments.CpuPoint> cpu = new ArrayList<>();
        final List<JfrDocuments.AllocPoint> allocation = new ArrayList<>();

        PlatformAcc(long javaThreadId) {
            this.javaThreadId = javaThreadId;
        }

        void apply(RecordedThread thread) {
            if (name == null || name.isBlank()) name = thread.getJavaName();
            if (osThreadId == null) osThreadId = thread.getOSThreadId();
        }

        JfrDocuments.PlatformThread freeze() {
            return new JfrDocuments.PlatformThread(
                javaThreadId, name, osThreadId, parentJavaThreadId, parentName,
                startEpochMs, endEpochMs, existedBefore, aliveAtEnd, startStack,
                List.copyOf(cpu), List.copyOf(allocation));
        }
    }

    private static final class VirtualAcc {
        Long startEpochMs;
        Long endEpochMs;
        List<String> stack = List.of();
        int pinned;
    }
}
