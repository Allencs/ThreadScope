package com.threadscope.engine.parser;

import com.threadscope.engine.pattern.DumpPatterns;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 多 Dump 切分器。
 *
 * `kill -3` 连续输出到 stdout 的日志文件里常包含多个 "Full thread dump" 段，
 * 直接解析会把不同时刻的线程混在一起。本类按 dump 头切分为独立段落，
 * 并尽量捕获每段前面的时间戳行 (jstack 输出格式: "2026-08-29 10:00:00")。
 */
public class MultiDumpSplitter {

    /** jstack 在 dump 头前打印的时间戳行 */
    private static final Pattern TIMESTAMP_LINE = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2})"
    );

    /**
     * 单个 dump 段。
     *
     * @param timestamp dump 抓取时间 (可能为 null — 原文件没有时间戳行)
     * @param content   该段的完整文本
     */
    public record DumpSegment(String timestamp, String content) {}

    /**
     * 将原始文本切分为一个或多个 dump 段。
     * 没有找到任何 dump 头时，整体作为单段返回 (兼容缺头部的简化 dump)。
     */
    public List<DumpSegment> split(String rawContent) {
        String[] lines = rawContent.split("\n", -1);

        // 找到所有 dump 头的行号及其前面的时间戳
        List<Integer> headerIndexes = new ArrayList<>();
        List<String> timestamps = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (DumpPatterns.DUMP_HEADER.matcher(lines[i]).matches()) {
                headerIndexes.add(i);
                timestamps.add(findPrecedingTimestamp(lines, i));
            }
        }

        if (headerIndexes.size() <= 1) {
            String ts = timestamps.isEmpty() ? null : timestamps.getFirst();
            return List.of(new DumpSegment(ts, rawContent));
        }

        // 按 dump 头切分；第一段从文件开头算起 (保留头部前的内容给第一段无妨)
        List<DumpSegment> segments = new ArrayList<>();
        for (int seg = 0; seg < headerIndexes.size(); seg++) {
            int start = seg == 0 ? 0 : headerIndexes.get(seg);
            int end = seg + 1 < headerIndexes.size() ? headerIndexes.get(seg + 1) : lines.length;
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(lines[i]).append('\n');
            }
            segments.add(new DumpSegment(timestamps.get(seg), sb.toString()));
        }
        return segments;
    }

    /** 在 dump 头上方最多回看 3 行，找时间戳 */
    private String findPrecedingTimestamp(String[] lines, int headerIndex) {
        for (int i = headerIndex - 1; i >= Math.max(0, headerIndex - 3); i--) {
            var m = TIMESTAMP_LINE.matcher(lines[i]);
            if (m.find()) return m.group(1);
        }
        return null;
    }
}
