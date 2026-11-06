package com.threadscope.jfr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 热点根列表和调用者树。有调用栈时按完整路径精确统计：每个节点的样本都是
 * "根方法 → … → 该节点"这条链上的样本，百分比是占父节点样本的比例，沿链相乘即占根的比例。
 * 旧版报告没有调用栈，退回按方法名聚合。
 */
public final class JfrHotspotQuery {

    private static final int DEPTH_LIMIT = 24;
    private static final int CHILD_LIMIT = 8;
    private static final int NODE_BUDGET = 240;
    private static final int FOLLOW_LIMIT = 3;

    private JfrHotspotQuery() {}

    public static JfrDocuments.HotspotList list(
            JfrDocuments.HotspotDocument doc, JfrStackIndex index, String query, String sort, int limit) {
        return list(doc, index, query, sort, limit, null);
    }

    /** range 为 null 统计整份录制；否则只统计落在这些时间桶里的样本（需要带时间信息的调用栈）。 */
    public static JfrDocuments.HotspotList list(JfrDocuments.HotspotDocument doc, JfrStackIndex index,
                                                String query, String sort, int limit, JfrDocuments.BucketRange range) {
        String needle = needle(query);
        boolean byInclusive = "inclusive".equalsIgnoreCase(sort);
        boolean ranged = range != null && index != null && index.timed;
        long[] weight = index == null ? null : index.weights(ranged ? range : null);
        Totals totals = ranged ? Totals.of(index, weight) : Totals.of(doc, index);
        int cap = Math.max(1, limit);
        List<String> keys = new ArrayList<>();
        for (String key : totals.keys()) {
            if (needle.isEmpty() || ranged) {
                long primary = byInclusive || !needle.isEmpty() ? totals.inclusive(key) : totals.self(key);
                if (primary <= 0) continue;
            }
            if (!needle.isEmpty() && !matches(key, needle)) continue;
            keys.add(key);
        }
        keys.sort(byInclusive
            ? Comparator.<String>comparingLong(totals::inclusive).reversed()
            : Comparator.<String>comparingLong(totals::self).reversed());
        if (keys.size() > cap) keys = keys.subList(0, cap);

        boolean[] hit = index == null ? null : index.matching(needle);
        int[] deepest = index == null ? null : index.deepestMatch(hit);
        JfrDocuments.CpuTimeline cpu = JfrCpuTimeline.build(doc);
        long total = Math.max(1, totals.total());
        List<JfrDocuments.HotspotRow> rows = new ArrayList<>();
        for (String key : keys) {
            JfrDocuments.MethodStat stat = doc.methods().get(key);
            long self = totals.self(key);
            long inclusive = totals.inclusive(key);
            boolean matched = !needle.isEmpty() && matches(key, needle);
            int[] budget = {NODE_BUDGET};
            // 按 self 看时，树只统计该方法在栈顶的样本；self 为 0（搜索命中的业务方法）时用 inclusive。
            boolean selfBasis = index != null && !byInclusive && self > 0;
            List<JfrDocuments.HotspotCaller> callers = index == null
                ? mergedCallers(doc, key, needle, new HashSet<>(), 1, budget)
                : pathCallers(index, weight, Slice.root(index, weight, index.id(key), selfBasis), hit, deepest, 1, budget);
            long[] timeline = stat == null ? new long[Math.max(1, doc.bucketCount())] : JfrCpuTimeline.dense(
                byInclusive ? stat.inclusiveBuckets() : stat.selfBuckets(), doc.bucketCount());
            rows.add(new JfrDocuments.HotspotRow(
                key, JfrRecordingParser.label(key),
                self, inclusive,
                percent(self, total), percent(inclusive, total),
                matched, matched && !callers.isEmpty(), selfBasis ? "self" : "inclusive",
                Arrays.stream(timeline).boxed().toList(), JfrCpuTimeline.machineCpu(cpu, timeline, ranged ? range : null),
                callers));
        }
        return new JfrDocuments.HotspotList(totals.total(), byInclusive ? "inclusive" : "self", index != null,
            totals.truncated(), index != null && index.timed,
            ranged ? range.from() : null, ranged ? range.to() : null, rows);
    }

    /** 展开被截断的节点。path 从热点根方法开始，到要展开的节点为止。 */
    public static List<JfrDocuments.HotspotCaller> expand(
            JfrDocuments.HotspotDocument doc, JfrStackIndex index, List<String> path, String basis, String query) {
        return expand(doc, index, path, basis, query, null);
    }

    public static List<JfrDocuments.HotspotCaller> expand(JfrDocuments.HotspotDocument doc, JfrStackIndex index,
                                                          List<String> path, String basis, String query,
                                                          JfrDocuments.BucketRange range) {
        if (path == null || path.isEmpty()) return List.of();
        String needle = needle(query);
        int[] budget = {NODE_BUDGET};
        if (index == null) return mergedCallers(doc, path.getLast(), needle, new HashSet<>(), 1, budget);
        long[] weight = index.weights(range);
        Slice node = Slice.root(index, weight, index.id(path.getFirst()), "self".equalsIgnoreCase(basis));
        for (int i = 1; i < path.size() && node.size() > 0; i++) node = node.descend(index, index.id(path.get(i)));
        boolean[] hit = index.matching(needle);
        return pathCallers(index, weight, node, hit, index.deepestMatch(hit), 1, budget);
    }

    /** 统计范围内每个方法的 self / inclusive。 */
    private record Totals(Iterable<String> keys, Map<String, long[]> counts, long total, Long truncated) {

        static Totals of(JfrDocuments.HotspotDocument doc, JfrStackIndex index) {
            Map<String, long[]> counts = new HashMap<>(doc.methods().size() * 2);
            doc.methods().forEach((key, stat) -> counts.put(key, new long[] {stat.self(), stat.inclusive()}));
            return new Totals(doc.methods().keySet(), counts, doc.totalSamples(),
                index == null ? null : index.truncatedSamples);
        }

        static Totals of(JfrStackIndex index, long[] weight) {
            int n = index.keys.size();
            long[] self = new long[n];
            long[] inclusive = new long[n];
            int[] seen = new int[n];
            long total = 0;
            long cut = 0;
            for (int s = 0; s < weight.length; s++) {
                long w = weight[s];
                if (w == 0) continue;
                total += w;
                if (index.truncated[s]) cut += w;
                int[] frames = index.frames[s];
                self[frames[0]] += w;
                for (int frame : frames) {
                    if (seen[frame] == s + 1) continue;
                    seen[frame] = s + 1;
                    inclusive[frame] += w;
                }
            }
            Map<String, long[]> counts = new HashMap<>(n * 2);
            for (int i = 0; i < n; i++) {
                if (inclusive[i] > 0) counts.put(index.keys.get(i), new long[] {self[i], inclusive[i]});
            }
            return new Totals(counts.keySet(), counts, total, cut);
        }

        long self(String key) {
            long[] c = counts.get(key);
            return c == null ? 0 : c[0];
        }

        long inclusive(String key) {
            long[] c = counts.get(key);
            return c == null ? 0 : c[1];
        }
    }

    private static List<JfrDocuments.HotspotCaller> pathCallers(JfrStackIndex index, long[] weight, Slice node,
                                                                boolean[] hit, int[] deepest, int depth, int[] budget) {
        if (depth > DEPTH_LIMIT || budget[0] <= 0 || node.size() == 0) return List.of();
        long total = 0;
        long cut = 0;
        long bottom = 0;
        Map<Integer, Group> byFrame = new HashMap<>();
        for (int i = 0; i < node.size(); i++) {
            int stack = node.stacks()[i];
            int next = node.positions()[i] + 1;
            long count = weight[stack];
            total += count;
            int[] frames = index.frames[stack];
            if (next >= frames.length) {
                if (index.truncated[stack]) cut += count;
                else bottom += count;
                continue;
            }
            byFrame.computeIfAbsent(frames[next], Group::new).add(i, count);
        }
        if (byFrame.isEmpty()) return List.of();

        List<Group> groups = new ArrayList<>(byFrame.values());
        groups.sort(Comparator.comparingLong((Group g) -> g.samples).reversed());
        int take = Math.min(CHILD_LIMIT, groups.size());
        if (budget[0] < take) return List.of();
        budget[0] -= take;
        int deepSlots = Math.min(FOLLOW_LIMIT, take);
        List<JfrDocuments.HotspotCaller> result = new ArrayList<>(take + 3);
        int followed = 0;
        for (int g = 0; g < take; g++) {
            Group group = groups.get(g);
            Slice child = group.slice(node);
            String key = index.keys.get(group.frame);
            boolean matched = hit != null && hit[group.frame];
            boolean hasCallers = child.hasCallers(index);
            boolean leadsToMatch = deepest != null && child.reaches(deepest);
            List<JfrDocuments.HotspotCaller> nested = List.of();
            if (hasCallers && (followed < deepSlots || matched || leadsToMatch)) {
                int[] share = {budget[0] / Math.max(1, deepSlots - followed)};
                int before = share[0];
                if (before > 0) nested = pathCallers(index, weight, child, hit, deepest, depth + 1, share);
                budget[0] -= before - share[0];
                followed++;
            }
            result.add(new JfrDocuments.HotspotCaller(
                key, JfrRecordingParser.label(key), group.samples, percent(group.samples, total), matched,
                hasCallers && nested.isEmpty(), nested));
        }
        long rest = 0;
        for (int g = take; g < groups.size(); g++) rest += groups.get(g).samples;
        if (rest > 0) result.add(summary("others", "其余 " + (groups.size() - take) + " 个调用者", rest, total));
        if (cut > 0) result.add(summary("truncated", "调用栈被截断（超出 JFR stackdepth）", cut, total));
        if (bottom > 0) result.add(summary("bottom", "到达栈底（线程入口）", bottom, total));
        return result;
    }

    private static JfrDocuments.HotspotCaller summary(String kind, String label, long samples, long total) {
        return new JfrDocuments.HotspotCaller(
            "@" + kind, label, samples, percent(samples, total), false, false, List.of(), kind);
    }

    /** 旧版报告：调用者按方法名聚合，二层以下反映的是全局分布。 */
    private static List<JfrDocuments.HotspotCaller> mergedCallers(
            JfrDocuments.HotspotDocument doc, String key, String needle, Set<String> stack, int depth, int[] budget) {
        if (depth > DEPTH_LIMIT || budget[0] <= 0 || !stack.add(key)) return List.of();
        JfrDocuments.MethodStat stat = doc.methods().get(key);
        if (stat == null || stat.callers().isEmpty()) {
            stack.remove(key);
            return List.of();
        }
        long denom = stat.callers().values().stream().mapToLong(Long::longValue).sum();
        List<String> children = new ArrayList<>(stat.callers().keySet());
        children.sort(Comparator.comparingLong((String child) -> stat.callers().get(child)).reversed());
        // 同一层要么完整列出（百分比加起来接近 100%），要么整层不展开，由前端按需加载。
        int take = Math.min(CHILD_LIMIT, children.size());
        if (budget[0] < take) {
            stack.remove(key);
            return List.of();
        }
        budget[0] -= take;
        int deepSlots = Math.min(FOLLOW_LIMIT, take);
        List<JfrDocuments.HotspotCaller> result = new ArrayList<>(take);
        int followed = 0;
        for (int i = 0; i < take; i++) {
            String child = children.get(i);
            long samples = stat.callers().get(child);
            boolean matched = matches(child, needle);
            JfrDocuments.MethodStat childStat = doc.methods().get(child);
            boolean hasCallers = childStat != null && !childStat.callers().isEmpty() && !stack.contains(child);
            List<JfrDocuments.HotspotCaller> nested = List.of();
            if (hasCallers && (followed < deepSlots || matched)) {
                int[] share = {budget[0] / Math.max(1, deepSlots - followed)};
                int before = share[0];
                if (before > 0) nested = mergedCallers(doc, child, needle, stack, depth + 1, share);
                budget[0] -= before - share[0];
                followed++;
            }
            result.add(new JfrDocuments.HotspotCaller(
                child, JfrRecordingParser.label(child), samples, percent(samples, denom), matched,
                hasCallers && nested.isEmpty(), nested));
        }
        stack.remove(key);
        return result;
    }

    static boolean matches(String methodKey, String needle) {
        if (needle == null || needle.isEmpty()) return false;
        return methodKey.toLowerCase(Locale.ROOT).contains(needle)
            || JfrRecordingParser.label(methodKey).toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String needle(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    private static double percent(long part, long total) {
        if (total <= 0) return 0;
        return Math.round(part * 10000.0 / total) / 100.0;
    }

    /** 调用者树上的一个节点：落在这条路径上的栈，以及该节点在每条栈里的位置。 */
    private record Slice(int[] stacks, int[] positions, int size) {

        /** 权重为 0 的栈（不在所选时间区间内）不进入任何节点。 */
        static Slice root(JfrStackIndex index, long[] weight, int id, boolean selfOnly) {
            int[] candidates = index.containing(id);
            int[] stacks = new int[candidates.length];
            int[] positions = new int[candidates.length];
            int n = 0;
            for (int stack : candidates) {
                if (weight[stack] == 0) continue;
                int[] frames = index.frames[stack];
                if (selfOnly) {
                    if (frames[0] == id) {
                        stacks[n] = stack;
                        positions[n++] = 0;
                    }
                    continue;
                }
                for (int i = 0; i < frames.length; i++) {
                    if (frames[i] == id) {
                        stacks[n] = stack;
                        positions[n++] = i;
                        break;
                    }
                }
            }
            return new Slice(stacks, positions, n);
        }

        Slice descend(JfrStackIndex index, int id) {
            int[] nextStacks = new int[size];
            int[] nextPositions = new int[size];
            int n = 0;
            for (int i = 0; i < size; i++) {
                int[] frames = index.frames[stacks[i]];
                int next = positions[i] + 1;
                if (id >= 0 && next < frames.length && frames[next] == id) {
                    nextStacks[n] = stacks[i];
                    nextPositions[n++] = next;
                }
            }
            return new Slice(nextStacks, nextPositions, n);
        }

        boolean hasCallers(JfrStackIndex index) {
            for (int i = 0; i < size; i++) {
                if (positions[i] + 1 < index.frames[stacks[i]].length) return true;
            }
            return false;
        }

        boolean reaches(int[] deepest) {
            for (int i = 0; i < size; i++) {
                if (deepest[stacks[i]] > positions[i]) return true;
            }
            return false;
        }
    }

    private static final class Group {
        final int frame;
        long samples;
        int[] members = new int[4];
        int size;

        Group(int frame) {
            this.frame = frame;
        }

        void add(int member, long count) {
            if (size == members.length) members = Arrays.copyOf(members, size * 2);
            members[size++] = member;
            samples += count;
        }

        Slice slice(Slice parent) {
            int[] stacks = new int[size];
            int[] positions = new int[size];
            for (int i = 0; i < size; i++) {
                stacks[i] = parent.stacks()[members[i]];
                positions[i] = parent.positions()[members[i]] + 1;
            }
            return new Slice(stacks, positions, size);
        }
    }
}
