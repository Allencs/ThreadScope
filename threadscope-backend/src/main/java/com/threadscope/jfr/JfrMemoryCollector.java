package com.threadscope.jfr;

import com.threadscope.jfr.JfrRecordingParser.FrameNames;
import com.threadscope.jfr.JfrRecordingParser.StackTable;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析过程中顺带收集内存相关事件：分配调用栈（TLAB / ObjectAllocationSample 两种来源分别统计）、
 * GC、堆占用、ZGC 分配停顿、元空间、RSS、容器内存，以及 ThreadAllocationStatistics 给出的实际分配量。
 */
final class JfrMemoryCollector {

    static final String TLAB = "tlab";
    static final String SAMPLE = "sample";

    private static final int SERIES_LIMIT = 43_200;
    private static final int GC_LIMIT = 50_000;
    private static final int HEAP_LIMIT = 100_000;
    private static final int STALL_LIMIT = 20_000;
    private static final int INTERVAL_LIMIT = 20_000;
    /**
     * 同一个 chunk 内相同的调用栈 / 方法是同一个对象，按引用缓存能省掉大部分转换。
     * 引用会让 chunk 的常量池无法回收，所以超过上限就清空。
     */
    private static final int CACHE_LIMIT = 200_000;

    private final Instant min;
    private final Instant max;
    private final int bucketCount;
    private final FrameNames names = new FrameNames();
    private final Map<RecordedStackTrace, Chain> chains = new IdentityHashMap<>();
    private final Map<RecordedMethod, Integer> methods = new IdentityHashMap<>();
    private final Map<String, Profile> profiles = new LinkedHashMap<>();

    private final long[] measured;
    private boolean anyMeasured;
    private final Map<Long, Long> lastAllocated = new HashMap<>();
    private final Map<Long, Long> lastAllocatedAt = new HashMap<>();
    private final List<Long> intervals = new ArrayList<>();

    private String youngCollector;
    private String oldCollector;
    private Long heapMax;
    private Long heapInitial;
    private final List<JfrDocuments.GcCollection> collections = new ArrayList<>();
    private final List<JfrDocuments.HeapPoint> heap = new ArrayList<>();
    private long stallCount;
    private final List<JfrDocuments.StallEvent> stalls = new ArrayList<>();
    private final Map<StackKey, Integer> stallStackIds = new HashMap<>();
    private final List<Chain> stallStacks = new ArrayList<>();
    private final List<JfrDocuments.MemoryPoint> metaspace = new ArrayList<>();
    private final List<JfrDocuments.MemoryPoint> rss = new ArrayList<>();
    private final List<JfrDocuments.MemoryPoint> container = new ArrayList<>();

    JfrMemoryCollector(Instant min, Instant max, int bucketCount) {
        this.min = min;
        this.max = max;
        this.bucketCount = bucketCount;
        this.measured = new long[bucketCount];
    }

    static boolean isAllocation(String name) {
        return switch (name) {
            case "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB", "jdk.ObjectAllocationSample" -> true;
            default -> false;
        };
    }

    void accept(RecordedEvent event, String name) {
        switch (name) {
            case "jdk.ObjectAllocationInNewTLAB" -> allocation(event, TLAB, "tlabSize");
            case "jdk.ObjectAllocationOutsideTLAB" -> allocation(event, TLAB, "allocationSize");
            case "jdk.ObjectAllocationSample" -> allocation(event, SAMPLE, "weight");
            case "jdk.ThreadAllocationStatistics" -> threadAllocation(event);
            case "jdk.GarbageCollection" -> {
                if (collections.size() < GC_LIMIT) collections.add(new JfrDocuments.GcCollection(
                    JfrRecordingParser.longOr(event, "gcId", -1),
                    event.getStartTime().toEpochMilli(),
                    string(event, "name"),
                    string(event, "cause"),
                    nanos(event.getDuration()),
                    JfrRecordingParser.nanosOr(event, "sumOfPauses"),
                    JfrRecordingParser.nanosOr(event, "longestPause")));
            }
            case "jdk.GCHeapSummary" -> {
                if (heap.size() >= HEAP_LIMIT || !JfrRecordingParser.has(event, "heapUsed")) return;
                long committed = 0;
                if (JfrRecordingParser.has(event, "heapSpace")) {
                    RecordedObject space = event.getValue("heapSpace");
                    if (space != null && space.hasField("committedSize")) committed = space.getLong("committedSize");
                }
                heap.add(new JfrDocuments.HeapPoint(event.getStartTime().toEpochMilli(),
                    "After GC".equalsIgnoreCase(string(event, "when")), event.getLong("heapUsed"), committed));
            }
            case "jdk.GCHeapConfiguration" -> {
                if (heapMax == null && JfrRecordingParser.has(event, "maxSize")) heapMax = positive(event.getLong("maxSize"));
                if (heapInitial == null && JfrRecordingParser.has(event, "initialSize")) heapInitial = positive(event.getLong("initialSize"));
            }
            case "jdk.GCConfiguration" -> {
                if (youngCollector == null) youngCollector = string(event, "youngCollector");
                if (oldCollector == null) oldCollector = string(event, "oldCollector");
            }
            case "jdk.ZAllocationStall" -> {
                stallCount++;
                if (stalls.size() >= STALL_LIMIT) return;
                RecordedThread thread = event.getThread();
                stalls.add(new JfrDocuments.StallEvent(event.getStartTime().toEpochMilli(), nanos(event.getDuration()),
                    thread == null ? null : thread.getJavaName(), string(event, "type"),
                    JfrRecordingParser.has(event, "size") ? positive(event.getLong("size")) : null, stallStack(event)));
            }
            case "jdk.MetaspaceSummary" -> {
                if (metaspace.size() >= SERIES_LIMIT || !JfrRecordingParser.has(event, "metaspace")) return;
                RecordedObject space = event.getValue("metaspace");
                if (space != null && space.hasField("used")) {
                    metaspace.add(new JfrDocuments.MemoryPoint(event.getStartTime().toEpochMilli(), space.getLong("used")));
                }
            }
            case "jdk.ResidentSetSize" -> point(event, "size", rss);
            case "jdk.ContainerMemoryUsage" -> point(event, "memoryUsage", container);
            default -> { }
        }
    }

    private void allocation(RecordedEvent event, String source, String weightField) {
        if (!JfrRecordingParser.has(event, weightField)) return;
        long weight = event.getLong(weightField);
        if (weight <= 0) return;
        Chain chain = chain(event);
        if (chain.frames().length == 0) return;
        Profile profile = profiles.computeIfAbsent(source, k -> new Profile(names, bucketCount));
        RecordedClass type = JfrRecordingParser.has(event, "objectClass") ? event.getClass("objectClass") : null;
        int typeId = profile.type(type == null ? "?" : typeName(type.getName()));
        Instant time = event.getStartTime();
        int bucket = JfrRecordingParser.bucketOf(time, min, max, bucketCount);
        profile.add(chain, typeId, bucket, weight, time.toEpochMilli());
    }

    private Integer stallStack(RecordedEvent event) {
        Chain chain = chain(event);
        if (chain.frames().length == 0) return null;
        return stallStackIds.computeIfAbsent(new StackKey(chain.frames(), chain.truncated()), k -> {
            stallStacks.add(chain);
            return stallStacks.size() - 1;
        });
    }

    private Chain chain(RecordedEvent event) {
        if (!JfrRecordingParser.has(event, "stackTrace")) return Chain.EMPTY;
        RecordedStackTrace stack = event.getStackTrace();
        if (stack == null) return Chain.EMPTY;
        Chain cached = chains.get(stack);
        if (cached != null) return cached;
        List<RecordedFrame> frames = stack.getFrames();
        int[] ids = new int[frames.size()];
        int n = 0;
        for (RecordedFrame frame : frames) {
            if (JfrRecordingParser.hidden(frame)) continue;
            RecordedMethod method = frame.getMethod();
            if (method == null) continue;
            Integer id = methods.get(method);
            if (id == null) {
                String key = JfrRecordingParser.methodKey(method);
                if (key == null) continue;
                id = names.id(key);
                if (methods.size() >= CACHE_LIMIT) methods.clear();
                methods.put(method, id);
            }
            if (n > 0 && ids[n - 1] == id) continue;
            ids[n++] = id;
        }
        Chain chain = new Chain(n == ids.length ? ids : Arrays.copyOf(ids, n), stack.isTruncated());
        if (chains.size() >= CACHE_LIMIT) chains.clear();
        chains.put(stack, chain);
        return chain;
    }

    /** allocated 是线程自启动以来的累计值，相邻两次的差值就是这段时间的实际分配量。 */
    private void threadAllocation(RecordedEvent event) {
        if (!JfrRecordingParser.has(event, "allocated")) return;
        RecordedThread thread = JfrRecordingParser.has(event, "thread") ? event.getThread("thread") : null;
        if (thread == null) return;
        long id = thread.getJavaThreadId();
        long allocated = event.getLong("allocated");
        long time = event.getStartTime().toEpochMilli();
        Long previous = lastAllocated.put(id, allocated);
        Long previousAt = lastAllocatedAt.put(id, time);
        if (previous == null || allocated < previous) return;
        long delta = allocated - previous;
        if (previousAt != null && time > previousAt && intervals.size() < INTERVAL_LIMIT) intervals.add(time - previousAt);
        if (delta == 0) return;
        spread(delta, previousAt == null ? time : previousAt, time);
        anyMeasured = true;
    }

    /** default 模版按 everyChunk 输出计数器，一个差值可能横跨几分钟，按时间比例摊到各桶，避免尖刺。 */
    private void spread(long delta, long fromMs, long toMs) {
        long start = min.toEpochMilli();
        long span = Math.max(1, max.toEpochMilli() - start);
        long from = Math.max(start, Math.min(fromMs, toMs));
        long to = Math.min(start + span, toMs);
        if (bucketCount <= 1 || to <= from) {
            measured[JfrRecordingParser.bucketOf(Instant.ofEpochMilli(toMs), min, max, bucketCount)] += delta;
            return;
        }
        double perMs = (double) delta / (to - from);
        long assigned = 0;
        int last = -1;
        for (int i = (int) ((from - start) * bucketCount / span); i < bucketCount; i++) {
            long bucketStart = start + (long) Math.ceil((double) i * span / bucketCount);
            long bucketEnd = start + (long) Math.ceil((double) (i + 1) * span / bucketCount);
            long overlap = Math.min(to, bucketEnd) - Math.max(from, bucketStart);
            if (overlap > 0) {
                long part = Math.round(overlap * perMs);
                measured[i] += part;
                assigned += part;
                last = i;
            }
            if (bucketEnd >= to) break;
        }
        if (last < 0) last = JfrRecordingParser.bucketOf(Instant.ofEpochMilli(to), min, max, bucketCount);
        measured[last] += delta - assigned;
    }

    private static void point(RecordedEvent event, String field, List<JfrDocuments.MemoryPoint> series) {
        if (series.size() >= SERIES_LIMIT || !JfrRecordingParser.has(event, field)) return;
        series.add(new JfrDocuments.MemoryPoint(event.getStartTime().toEpochMilli(), event.getLong(field)));
    }

    JfrDocuments.MemoryDocument freeze(Long containerLimit) {
        List<JfrDocuments.AllocationProfile> frozen = new ArrayList<>();
        List<String> frames = names.list();
        for (String source : List.of(TLAB, SAMPLE)) {
            Profile profile = profiles.get(source);
            if (profile != null) frozen.add(profile.freeze(source, frames, min, max, bucketCount));
        }
        Long interval = null;
        if (!intervals.isEmpty()) {
            long[] sorted = intervals.stream().mapToLong(Long::longValue).sorted().toArray();
            interval = sorted[sorted.length / 2];
        }
        Map<Integer, Integer> local = new HashMap<>();
        List<String> stallFrames = new ArrayList<>();
        List<JfrDocuments.StallStack> stallTraces = new ArrayList<>(stallStacks.size());
        for (Chain chain : stallStacks) {
            List<Integer> ids = new ArrayList<>(chain.frames().length);
            for (int id : chain.frames()) {
                ids.add(local.computeIfAbsent(id, k -> {
                    stallFrames.add(frames.get(k));
                    return stallFrames.size() - 1;
                }));
            }
            stallTraces.add(new JfrDocuments.StallStack(ids, chain.truncated()));
        }
        var gc = new JfrDocuments.GcDocument(youngCollector, oldCollector, heapMax, heapInitial,
            sorted(collections), heap, stallCount, stalls, metaspace, rss, container, containerLimit,
            stallFrames, stallTraces);
        return new JfrDocuments.MemoryDocument(min.toEpochMilli(), max.toEpochMilli(), bucketCount, frozen,
            anyMeasured ? Arrays.stream(measured).boxed().toList() : null, interval, gc);
    }

    private static List<JfrDocuments.GcCollection> sorted(List<JfrDocuments.GcCollection> list) {
        list.sort((a, b) -> Long.compare(a.epochMs(), b.epochMs()));
        return list;
    }

    /** RecordedClass 对数组返回描述符形式，例如 "[B"、"[Ljava.lang.String;"。 */
    static String typeName(String name) {
        if (name == null || name.isEmpty() || name.charAt(0) != '[') return name;
        int dims = 0;
        while (dims < name.length() && name.charAt(dims) == '[') dims++;
        String element = switch (name.length() > dims ? name.charAt(dims) : '?') {
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'D' -> "double";
            case 'F' -> "float";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'S' -> "short";
            case 'Z' -> "boolean";
            case 'L' -> name.substring(dims + 1, name.endsWith(";") ? name.length() - 1 : name.length()).replace('/', '.');
            default -> name.substring(dims);
        };
        return element + "[]".repeat(dims);
    }

    private static String string(RecordedEvent event, String field) {
        return JfrRecordingParser.has(event, field) ? event.getString(field) : null;
    }

    private static long nanos(Duration duration) {
        return duration == null || duration.isNegative() ? 0 : duration.toNanos();
    }

    private static Long positive(long value) {
        return value > 0 && value != Long.MAX_VALUE ? value : null;
    }

    private record Chain(int[] frames, boolean truncated) {
        static final Chain EMPTY = new Chain(new int[0], false);
    }

    /** 引用缓存会被清空，同一条栈可能对应多个 Chain 对象，去重要按内容比较。 */
    private record StackKey(int[] frames, boolean truncated) {
        @Override
        public boolean equals(Object o) {
            return o instanceof StackKey k && truncated == k.truncated && Arrays.equals(frames, k.frames);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(frames) * 31 + (truncated ? 1 : 0);
        }
    }

    private static final class Profile {
        final StackTable table;
        final Map<String, Integer> typeIds = new HashMap<>();
        final List<String> types = new ArrayList<>();
        final long[] buckets;
        long events;
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;

        Profile(FrameNames names, int bucketCount) {
            this.table = new StackTable(names);
            this.buckets = new long[bucketCount];
        }

        int type(String name) {
            return typeIds.computeIfAbsent(name, k -> {
                types.add(k);
                return types.size() - 1;
            });
        }

        void add(Chain chain, int type, int bucket, long weight, long epochMs) {
            table.add(chain.frames(), chain.truncated(), bucket, weight, type);
            buckets[bucket] += weight;
            events++;
            first = Math.min(first, epochMs);
            last = Math.max(last, epochMs);
        }

        JfrDocuments.AllocationProfile freeze(String source, List<String> frames, Instant min, Instant max, int bucketCount) {
            long total = 0;
            for (long b : buckets) total += b;
            var stacks = new JfrDocuments.HotspotDocument(total, min.toEpochMilli(), max.toEpochMilli(), bucketCount,
                Arrays.stream(buckets).boxed().toList(), Map.of(), frames, table.freeze(), null);
            return new JfrDocuments.AllocationProfile(source, events, first, last, List.copyOf(types), stacks);
        }
    }
}
