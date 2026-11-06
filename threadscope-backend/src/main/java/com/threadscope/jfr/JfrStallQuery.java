package com.threadscope.jfr;

import com.threadscope.engine.analyzer.CodeLayerClassifier;
import com.threadscope.model.CodeLayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ZGC 分配停顿明细：按完整调用栈或按首个业务帧把停顿事件分组，回答「哪段代码在等 GC」。
 * 停顿事件最多两万条，每次查询直接遍历，不需要索引。
 */
final class JfrStallQuery {

    static final String STACK = "stack";
    static final String BUSINESS = "business";

    private static final String NO_STACK = "-";
    private static final int GROUP_LIMIT = 100;
    private static final int TRACE_LIMIT = 3;
    private static final int EVENT_LIMIT = 30;
    private static final int THREAD_LIMIT = 5;

    private JfrStallQuery() {}

    static JfrDocuments.StallReport report(JfrDocuments.MemoryDocument doc, String group, String thread,
                                           JfrDocuments.BucketRange range, CodeLayerClassifier classifier) {
        JfrDocuments.GcDocument gc = doc.gc();
        String mode = BUSINESS.equals(group) ? BUSINESS : STACK;
        String needle = thread == null ? "" : thread.trim().toLowerCase(Locale.ROOT);
        List<String> frames = gc.stallFrames() == null ? List.of() : gc.stallFrames();
        List<JfrDocuments.StallStack> stacks = gc.stallStacks() == null ? List.of() : gc.stallStacks();
        Layers layers = new Layers(frames, classifier);
        Integer[] anchors = new Integer[stacks.size()];

        Map<String, Group> groups = new LinkedHashMap<>();
        Map<String, Boolean> threads = new HashMap<>();
        long count = 0;
        long total = 0;
        long longest = 0;
        for (JfrDocuments.StallEvent e : gc.stalls()) {
            String name = e.thread() == null ? "?" : e.thread();
            if (!needle.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(needle)) continue;
            if (range != null && !inRange(doc, e.epochMs(), range)) continue;
            count++;
            total += e.durationNanos();
            longest = Math.max(longest, e.durationNanos());
            threads.put(name, Boolean.TRUE);

            Integer s = e.stack() != null && e.stack() >= 0 && e.stack() < stacks.size() ? e.stack() : null;
            String key;
            boolean business = false;
            if (s == null) {
                key = NO_STACK;
            } else if (STACK.equals(mode)) {
                key = "s" + s;
                business = anchor(anchors, stacks, layers, s) >= 0;
            } else {
                List<Integer> f = stacks.get(s).frames();
                int a = anchor(anchors, stacks, layers, s);
                key = frames.get(f.get(Math.max(a, 0)));
                business = a >= 0;
            }
            boolean isBusiness = business;
            groups.computeIfAbsent(key, k -> new Group(k, isBusiness)).add(e, name, s);
        }

        long denominator = total;
        List<Group> ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.comparingLong((Group g) -> g.duration).reversed());
        List<JfrDocuments.StallGroup> rows = ordered.stream().limit(GROUP_LIMIT)
            .map(g -> g.freeze(mode, frames, stacks, layers, anchors, denominator))
            .toList();
        return new JfrDocuments.StallReport(mode, needle.isEmpty() ? null : thread.trim(),
            range == null ? null : range.from(), range == null ? null : range.to(),
            !stacks.isEmpty(), count, total, longest, threads.size(), groups.size(), rows);
    }

    private static boolean inRange(JfrDocuments.MemoryDocument doc, long epochMs, JfrDocuments.BucketRange range) {
        long start = doc.startEpochMs();
        long end = doc.endEpochMs();
        if (epochMs < start || epochMs > end) return false;
        int n = Math.max(1, doc.bucketCount());
        int bucket = n <= 1 ? 0 : (int) Math.min(n - 1, (epochMs - start) * n / Math.max(1, end - start));
        return bucket >= range.from() && bucket < range.to();
    }

    /** 从栈顶数第一个业务帧的位置，没有为 -1。 */
    private static int anchor(Integer[] anchors, List<JfrDocuments.StallStack> stacks, Layers layers, int s) {
        if (anchors[s] == null) {
            List<Integer> f = stacks.get(s).frames();
            int a = -1;
            for (int i = 0; i < f.size(); i++) {
                if (layers.business(f.get(i))) {
                    a = i;
                    break;
                }
            }
            anchors[s] = a;
        }
        return anchors[s];
    }

    private static final class Layers {
        final List<String> frames;
        final CodeLayerClassifier classifier;
        final byte[] layer;

        Layers(List<String> frames, CodeLayerClassifier classifier) {
            this.frames = frames;
            this.classifier = classifier;
            this.layer = new byte[frames.size()];
        }

        boolean business(int frame) {
            if (layer[frame] == 0) {
                String key = frames.get(frame);
                int hash = key.indexOf('#');
                layer[frame] = classifier.classify(hash < 0 ? key : key.substring(0, hash)) == CodeLayer.BUSINESS ? (byte) 1 : (byte) 2;
            }
            return layer[frame] == 1;
        }
    }

    private static final class Group {
        final String key;
        final boolean business;
        long count;
        long duration;
        long longest;
        long size;
        final Map<String, long[]> threads = new HashMap<>();
        final Map<String, Long> types = new LinkedHashMap<>();
        final Map<Integer, long[]> stacks = new HashMap<>();
        final List<JfrDocuments.StallEvent> events = new ArrayList<>();

        Group(String key, boolean business) {
            this.key = key;
            this.business = business;
        }

        void add(JfrDocuments.StallEvent e, String thread, Integer stack) {
            count++;
            duration += e.durationNanos();
            longest = Math.max(longest, e.durationNanos());
            if (e.size() != null) size += e.size();
            long[] t = threads.computeIfAbsent(thread, k -> new long[2]);
            t[0]++;
            t[1] += e.durationNanos();
            types.merge(e.type() == null ? "?" : e.type(), 1L, Long::sum);
            if (stack != null) {
                long[] st = stacks.computeIfAbsent(stack, k -> new long[2]);
                st[0]++;
                st[1] += e.durationNanos();
            }
            events.add(e);
        }

        JfrDocuments.StallGroup freeze(String mode, List<String> frames, List<JfrDocuments.StallStack> all,
                                       Layers layers, Integer[] anchors, long total) {
            List<JfrDocuments.StallThread> topThreads = threads.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .limit(THREAD_LIMIT)
                .map(e -> new JfrDocuments.StallThread(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
            List<JfrDocuments.StallTrace> traces = stacks.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .limit(TRACE_LIMIT)
                .map(e -> trace(all.get(e.getKey()), anchor(anchors, all, layers, e.getKey()), frames, layers,
                    e.getValue()[0], e.getValue()[1]))
                .toList();
            List<JfrDocuments.StallEventRow> slowest = events.stream()
                .sorted(Comparator.comparingLong(JfrDocuments.StallEvent::durationNanos).reversed())
                .limit(EVENT_LIMIT)
                .map(e -> new JfrDocuments.StallEventRow(e.epochMs(), e.durationNanos(), e.thread(), e.type(), e.size()))
                .toList();
            String label;
            if (NO_STACK.equals(key)) {
                label = "（没有调用栈）";
            } else if (STACK.equals(mode)) {
                List<Integer> f = all.get(Integer.parseInt(key.substring(1))).frames();
                label = JfrRecordingParser.label(frames.get(f.get(0)));
            } else {
                label = JfrRecordingParser.label(key);
            }
            return new JfrDocuments.StallGroup(key, label, business, count, duration, longest, size,
                JfrHotspotQuery.percent(duration, total), threads.size(), topThreads, types, stacks.size(), traces, slowest);
        }
    }

    private static JfrDocuments.StallTrace trace(JfrDocuments.StallStack stack, int anchor, List<String> frames,
                                                 Layers layers, long count, long duration) {
        List<JfrDocuments.StallFrame> list = new ArrayList<>(stack.frames().size());
        for (int f : stack.frames()) {
            String key = frames.get(f);
            list.add(new JfrDocuments.StallFrame(key, JfrRecordingParser.label(key), layers.business(f)));
        }
        return new JfrDocuments.StallTrace(list, anchor >= 0 ? anchor : null, stack.truncated(), count, duration);
    }
}
