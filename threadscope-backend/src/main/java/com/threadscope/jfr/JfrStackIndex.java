package com.threadscope.jfr;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 去重调用栈的倒排索引：方法 → 包含它的栈。只在内存里按报告缓存，不入库。
 */
final class JfrStackIndex {

    final List<String> keys;
    final int[][] frames;
    final long[] samples;
    final boolean[] truncated;
    final long truncatedSamples;
    /** 每条栈的稀疏时间桶分布；旧版报告没有，timed 为 false。 */
    final boolean timed;
    /** 分配栈的对象类型下标；CPU 栈为 null。 */
    final int[] types;
    private final int[][] buckets;
    private final long[][] bucketSamples;
    private final Map<String, Integer> ids;
    private final int[][] containing;

    private JfrStackIndex(List<String> keys, List<JfrDocuments.StackSample> stacks) {
        this.keys = keys;
        int n = stacks.size();
        frames = new int[n][];
        samples = new long[n];
        truncated = new boolean[n];
        buckets = new int[n][];
        bucketSamples = new long[n][];
        ids = new HashMap<>(keys.size() * 2);
        for (int i = 0; i < keys.size(); i++) ids.put(keys.get(i), i);

        int[] sizes = new int[keys.size()];
        int[] seen = new int[keys.size()];
        long cut = 0;
        boolean allTimed = n > 0;
        boolean typed = n > 0 && stacks.getFirst().type() != null;
        types = typed ? new int[n] : null;
        for (int s = 0; s < n; s++) {
            JfrDocuments.StackSample stack = stacks.get(s);
            frames[s] = stack.frames();
            if (typed) types[s] = stack.type() == null ? -1 : stack.type();
            samples[s] = stack.samples();
            truncated[s] = stack.truncated();
            buckets[s] = stack.buckets();
            bucketSamples[s] = stack.bucketSamples();
            if (buckets[s] == null || bucketSamples[s] == null) allTimed = false;
            if (truncated[s]) cut += samples[s];
            for (int frame : frames[s]) {
                if (seen[frame] == s + 1) continue;
                seen[frame] = s + 1;
                sizes[frame]++;
            }
        }
        truncatedSamples = cut;
        timed = allTimed;
        containing = new int[keys.size()][];
        for (int i = 0; i < sizes.length; i++) containing[i] = new int[sizes[i]];
        int[] fill = new int[keys.size()];
        Arrays.fill(seen, 0);
        for (int s = 0; s < n; s++) {
            for (int frame : frames[s]) {
                if (seen[frame] == s + 1) continue;
                seen[frame] = s + 1;
                containing[frame][fill[frame]++] = s;
            }
        }
    }

    /** 旧版报告没有保存调用栈时返回 null。 */
    static JfrStackIndex of(JfrDocuments.HotspotDocument doc) {
        if (doc == null || doc.frames() == null || doc.stacks() == null) return null;
        return new JfrStackIndex(doc.frames(), doc.stacks());
    }

    int id(String key) {
        Integer id = key == null ? null : ids.get(key);
        return id == null ? -1 : id;
    }

    int[] containing(int id) {
        return id < 0 ? new int[0] : containing[id];
    }

    /** 每条栈在时间桶 [from, to) 内的样本数；range 为 null 时就是全部样本。 */
    long[] weights(JfrDocuments.BucketRange range) {
        if (range == null || !timed) return samples;
        long[] weights = new long[frames.length];
        for (int s = 0; s < frames.length; s++) {
            int[] b = buckets[s];
            long[] c = bucketSamples[s];
            long sum = 0;
            for (int i = 0; i < b.length && b[i] < range.to(); i++) {
                if (b[i] >= range.from()) sum += c[i];
            }
            weights[s] = sum;
        }
        return weights;
    }

    /** 在 {@link #weights(JfrDocuments.BucketRange)} 的基础上只保留对象类型为 type 的栈；type < 0 不过滤。 */
    long[] weights(JfrDocuments.BucketRange range, int type) {
        long[] base = weights(range);
        if (type < 0 || types == null) return base;
        long[] filtered = new long[base.length];
        for (int s = 0; s < base.length; s++) {
            if (types[s] == type) filtered[s] = base[s];
        }
        return filtered;
    }

    /** 把栈 s 的时间桶分布累加到 into；旧版报告没有时间信息时什么也不做。 */
    void addBuckets(int s, long[] into) {
        int[] b = buckets[s];
        long[] c = bucketSamples[s];
        if (b == null || c == null) return;
        for (int i = 0; i < b.length; i++) {
            if (b[i] >= 0 && b[i] < into.length) into[b[i]] += c[i];
        }
    }

    /** 每个方法是否匹配搜索词；needle 为空时返回 null。 */
    boolean[] matching(String needle) {
        if (needle == null || needle.isEmpty()) return null;
        boolean[] hit = new boolean[keys.size()];
        for (int i = 0; i < hit.length; i++) {
            String key = keys.get(i);
            hit[i] = key.toLowerCase(Locale.ROOT).contains(needle)
                || JfrRecordingParser.label(key).toLowerCase(Locale.ROOT).contains(needle);
        }
        return hit;
    }

    /** 每条栈里离栈底最近的匹配帧位置，没有匹配为 -1，用来判断往上走能否到达搜索目标。 */
    int[] deepestMatch(boolean[] hit) {
        if (hit == null) return null;
        int[] deepest = new int[frames.length];
        for (int s = 0; s < frames.length; s++) {
            int found = -1;
            int[] stack = frames[s];
            for (int i = stack.length - 1; i >= 0; i--) {
                if (hit[stack[i]]) {
                    found = i;
                    break;
                }
            }
            deepest[s] = found;
        }
        return deepest;
    }
}
