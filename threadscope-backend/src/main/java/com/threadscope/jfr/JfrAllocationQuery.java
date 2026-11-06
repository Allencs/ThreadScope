package com.threadscope.jfr;

import com.threadscope.engine.analyzer.CodeLayerClassifier;
import com.threadscope.model.CodeLayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分配热点。与 CPU 热点共用调用栈索引和调用者树，权重换成估算的分配字节数，另外支持：
 * <ul>
 *   <li>按对象类型过滤；</li>
 *   <li>business 口径：每条分配栈从栈顶往下找第一个业务方法，字节数只记给它。JDK / 框架帧上的分配
 *       （Arrays.copyOf、StringBuilder、Jackson ...）都会归到真正发起它的业务代码；</li>
 *   <li>每个方法列出主要的分配点（栈顶方法 + 对象类型）。</li>
 * </ul>
 */
final class JfrAllocationQuery {

    static final String SELF = "self";
    static final String INCLUSIVE = "inclusive";
    static final String BUSINESS = "business";

    private static final int SITE_LIMIT = 6;
    private static final int TYPE_LIMIT = 40;

    private JfrAllocationQuery() {}

    static JfrDocuments.AllocationList list(JfrDocuments.AllocationProfile profile, JfrStackIndex index,
                                            String query, String sort, int limit, JfrDocuments.BucketRange range,
                                            String typeName, CodeLayerClassifier classifier) {
        String basis = basis(sort);
        String needle = JfrHotspotQuery.needle(query);
        int type = typeId(profile, typeName);
        boolean ranged = range != null && index.timed;
        long[] rangeWeight = index.weights(ranged ? range : null);
        long[] weight = type == -2 ? new long[rangeWeight.length] : index.weights(ranged ? range : null, type);
        long[] baseWeight = type == -2 ? weight : index.weights(null, type);

        int n = index.keys.size();
        long[] self = new long[n];
        long[] inclusive = new long[n];
        long[] attributed = new long[n];
        int[] seen = new int[n];
        int[] anchor = BUSINESS.equals(basis) ? anchors(index, classifier) : null;
        long total = 0;
        long cut = 0;
        long unattributed = 0;
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
            if (anchor != null) {
                if (anchor[s] >= 0) attributed[frames[anchor[s]]] += w;
                else unattributed += w;
            }
        }

        long[] primary = switch (basis) {
            case SELF -> self;
            case BUSINESS -> attributed;
            default -> inclusive;
        };
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (inclusive[i] <= 0) continue;
            if (needle.isEmpty() ? primary[i] <= 0 : !JfrHotspotQuery.matches(index.keys.get(i), needle)) continue;
            ids.add(i);
        }
        ids.sort(Comparator.<Integer>comparingLong(i -> primary[i]).reversed()
            .thenComparing(Comparator.<Integer>comparingLong(i -> inclusive[i]).reversed()));
        if (ids.size() > Math.max(1, limit)) ids = ids.subList(0, Math.max(1, limit));

        boolean[] hit = index.matching(needle);
        int[] deepest = index.deepestMatch(hit);
        List<JfrDocuments.AllocationRow> rows = new ArrayList<>(ids.size());
        for (int id : ids) {
            String key = index.keys.get(id);
            // 搜索命中的方法自己不一定是分配点 / 首个业务帧，这时退回 inclusive。
            String rowBasis = primary[id] > 0 ? basis : INCLUSIVE;
            JfrHotspotQuery.Slice root = root(index, weight, id, rowBasis, anchor);
            int[] budget = {JfrHotspotQuery.NODE_BUDGET};
            List<JfrDocuments.HotspotCaller> callers =
                JfrHotspotQuery.pathCallers(index, weight, root, hit, deepest, 1, budget);
            boolean matched = !needle.isEmpty() && JfrHotspotQuery.matches(key, needle);
            rows.add(new JfrDocuments.AllocationRow(
                key, JfrRecordingParser.label(key),
                self[id], inclusive[id],
                anchor == null ? null : attributed[id],
                JfrHotspotQuery.percent(self[id], total),
                JfrHotspotQuery.percent(inclusive[id], total),
                anchor == null ? null : JfrHotspotQuery.percent(attributed[id], total),
                matched, matched && !callers.isEmpty(), rowBasis,
                timeline(index, root(index, baseWeight, id, rowBasis, anchor), profile.stacks().bucketCount()),
                sites(index, weight, root, profile.types()),
                callers));
        }
        return new JfrDocuments.AllocationList(profile.source(), basis, total, sum(rangeWeight),
            type >= 0 ? profile.types().get(type) : null, index.timed,
            ranged ? range.from() : null, ranged ? range.to() : null,
            cut, anchor == null ? null : unattributed, types(index, rangeWeight, profile.types()), rows);
    }

    /** path 从根方法开始，到要展开的节点为止。 */
    static List<JfrDocuments.HotspotCaller> expand(JfrDocuments.AllocationProfile profile, JfrStackIndex index,
                                                    List<String> path, String basis, String query,
                                                    JfrDocuments.BucketRange range, String typeName,
                                                    CodeLayerClassifier classifier) {
        if (path == null || path.isEmpty()) return List.of();
        int type = typeId(profile, typeName);
        if (type == -2) return List.of();
        long[] weight = index.weights(range != null && index.timed ? range : null, type);
        String b = basis(basis);
        int[] anchor = BUSINESS.equals(b) ? anchors(index, classifier) : null;
        JfrHotspotQuery.Slice node = root(index, weight, index.id(path.getFirst()), b, anchor);
        for (int i = 1; i < path.size() && node.size() > 0; i++) node = node.descend(index, index.id(path.get(i)));
        boolean[] hit = index.matching(JfrHotspotQuery.needle(query));
        int[] budget = {JfrHotspotQuery.NODE_BUDGET};
        return JfrHotspotQuery.pathCallers(index, weight, node, hit, index.deepestMatch(hit), 1, budget);
    }

    static String basis(String sort) {
        if (SELF.equalsIgnoreCase(sort)) return SELF;
        if (BUSINESS.equalsIgnoreCase(sort)) return BUSINESS;
        return INCLUSIVE;
    }

    private static JfrHotspotQuery.Slice root(JfrStackIndex index, long[] weight, int id, String basis, int[] anchor) {
        if (BUSINESS.equals(basis) && anchor != null) return JfrHotspotQuery.Slice.anchored(index, weight, id, anchor);
        return JfrHotspotQuery.Slice.root(index, weight, id, SELF.equals(basis));
    }

    /** -1 不过滤；-2 表示指定的类型在这份录制里不存在。 */
    private static int typeId(JfrDocuments.AllocationProfile profile, String typeName) {
        if (typeName == null || typeName.isBlank()) return -1;
        int at = profile.types().indexOf(typeName);
        return at < 0 ? -2 : at;
    }

    /** 每条栈里从栈顶数第一个业务帧的位置，没有业务帧为 -1。 */
    static int[] anchors(JfrStackIndex index, CodeLayerClassifier classifier) {
        int n = index.keys.size();
        byte[] layer = new byte[n];
        int[] anchor = new int[index.frames.length];
        for (int s = 0; s < anchor.length; s++) {
            int[] frames = index.frames[s];
            anchor[s] = -1;
            for (int i = 0; i < frames.length; i++) {
                int f = frames[i];
                if (layer[f] == 0) layer[f] = business(classifier, index.keys.get(f)) ? (byte) 1 : (byte) 2;
                if (layer[f] == 1) {
                    anchor[s] = i;
                    break;
                }
            }
        }
        return anchor;
    }

    private static boolean business(CodeLayerClassifier classifier, String methodKey) {
        int hash = methodKey.indexOf('#');
        return classifier.classify(hash < 0 ? methodKey : methodKey.substring(0, hash)) == CodeLayer.BUSINESS;
    }

    private static List<Long> timeline(JfrStackIndex index, JfrHotspotQuery.Slice slice, int bucketCount) {
        long[] dense = new long[Math.max(1, bucketCount)];
        for (int i = 0; i < slice.size(); i++) index.addBuckets(slice.stacks()[i], dense);
        return Arrays.stream(dense).boxed().toList();
    }

    private static List<JfrDocuments.AllocationSite> sites(JfrStackIndex index, long[] weight,
                                                           JfrHotspotQuery.Slice slice, List<String> types) {
        Map<Long, long[]> bySite = new HashMap<>();
        long total = 0;
        for (int i = 0; i < slice.size(); i++) {
            int s = slice.stacks()[i];
            long w = weight[s];
            if (w == 0) continue;
            total += w;
            int type = index.types == null ? -1 : index.types[s];
            long key = ((long) index.frames[s][0] << 32) | (type & 0xffffffffL);
            bySite.computeIfAbsent(key, k -> new long[1])[0] += w;
        }
        long denominator = total;
        return bySite.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
            .limit(SITE_LIMIT)
            .map(e -> {
                String key = index.keys.get((int) (e.getKey() >>> 32));
                int type = (int) (long) e.getKey();
                return new JfrDocuments.AllocationSite(key, JfrRecordingParser.label(key),
                    type >= 0 && type < types.size() ? types.get(type) : null,
                    e.getValue()[0], JfrHotspotQuery.percent(e.getValue()[0], denominator));
            })
            .toList();
    }

    private static List<JfrDocuments.TypeRow> types(JfrStackIndex index, long[] weight, List<String> names) {
        if (index.types == null) return List.of();
        long[] bytes = new long[names.size()];
        long total = 0;
        for (int s = 0; s < weight.length; s++) {
            if (weight[s] == 0 || index.types[s] < 0) continue;
            bytes[index.types[s]] += weight[s];
            total += weight[s];
        }
        long denominator = total;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < bytes.length; i++) if (bytes[i] > 0) order.add(i);
        order.sort((a, b) -> Long.compare(bytes[b], bytes[a]));
        return order.stream().limit(TYPE_LIMIT)
            .map(i -> new JfrDocuments.TypeRow(names.get(i), bytes[i], JfrHotspotQuery.percent(bytes[i], denominator)))
            .toList();
    }

    private static long sum(long[] values) {
        long total = 0;
        for (long v : values) total += v;
        return total;
    }
}
