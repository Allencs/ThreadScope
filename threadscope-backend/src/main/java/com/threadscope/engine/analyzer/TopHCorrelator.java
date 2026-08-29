package com.threadscope.engine.analyzer;

import com.threadscope.model.ThreadInfo;
import com.threadscope.model.ThreadState;

import java.util.*;

/**
 * top -H 输出关联器 — 把操作系统层面的线程 CPU 占用映射回 Java 线程。
 *
 * `top -H -p <pid>` 输出的 PID 列是 OS 线程 ID (十进制)，
 * 与 dump 头部的 nid (十六进制) 是同一个值。关联后即可回答
 * "占 CPU 最高的那个 OS 线程对应哪个 Java 线程、它在跑什么代码"。
 */
public class TopHCorrelator {

    /** 关联结果条目 */
    public record CorrelatedThread(
        String threadName,
        String nid,
        long nidDecimal,
        double cpuPercent,
        ThreadState state,
        String topMethod
    ) {}

    /**
     * 关联结果。
     *
     * @param threads       成功关联的线程，按 CPU 降序
     * @param parsedEntries top 输出中解析出的行数
     * @param matched       其中成功匹配到 Java 线程的数量
     */
    public record CorrelationResult(
        List<CorrelatedThread> threads,
        int parsedEntries,
        int matched
    ) {}

    /**
     * 解析 top -H 输出并关联到线程。
     *
     * 兼容标准 top 批处理格式:
     *   PID USER      PR  NI    VIRT    RES    SHR S  %CPU  %MEM     TIME+ COMMAND
     *  1234 app       20   0 8912345 456789  12345 R  93.8   5.6   1:23.45 java
     */
    public CorrelationResult correlate(String topOutput, List<ThreadInfo> threads) {
        Map<Long, ThreadInfo> byNid = new HashMap<>();
        for (ThreadInfo t : threads) {
            if (t.nidDecimal() > 0) {
                byNid.putIfAbsent(t.nidDecimal(), t);
            }
        }

        List<CorrelatedThread> correlated = new ArrayList<>();
        int parsedEntries = 0;

        for (String line : topOutput.split("\n")) {
            String[] tokens = line.trim().split("\\s+");
            if (tokens.length < 9) continue;

            long pid = parseLongOrMinus(tokens[0]);
            if (pid <= 0) continue;

            // 标准 top 布局中 %CPU 是第 9 列；容错：从第 9 列开始找第一个合法百分比
            double cpu = -1;
            for (int i = 8; i < Math.min(tokens.length, 10); i++) {
                cpu = parseDoubleOrMinus(tokens[i]);
                if (cpu >= 0) break;
            }
            if (cpu < 0) continue;

            parsedEntries++;
            ThreadInfo thread = byNid.get(pid);
            if (thread != null) {
                correlated.add(new CorrelatedThread(
                    thread.name(),
                    thread.nid(),
                    thread.nidDecimal(),
                    cpu,
                    thread.state(),
                    thread.topMethod()
                ));
            }
        }

        correlated.sort(Comparator.comparingDouble(CorrelatedThread::cpuPercent).reversed());
        return new CorrelationResult(correlated, parsedEntries, correlated.size());
    }

    private static long parseLongOrMinus(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double parseDoubleOrMinus(String s) {
        try {
            // top 在部分 locale 下用逗号作小数点
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
