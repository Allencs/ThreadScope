package com.threadscope.model;

import java.util.List;

/**
 * 一次上传的完整分析 — 每份 dump 快照各自完整分析，最新一份为默认视图。
 *
 * 快照通过 ID 寻址：基础 ID 指向最新快照；"基础ID~sN" 指向第 N 份 (从 0 开始)。
 * 这样前端切换快照只需切换路由里的 analysisId，所有查询接口无需改造。
 */
public record AnalysisBundle(
    String analysisId,
    List<AnalysisResult> snapshots
) {
    public static final String SNAPSHOT_SEPARATOR = "~s";

    public AnalysisResult latest() {
        return snapshots.getLast();
    }

    public int totalThreads() {
        return snapshots.stream().mapToInt(AnalysisResult::totalThreads).sum();
    }

    public static String snapshotId(String baseId, int index, int count) {
        return index == count - 1 ? baseId : baseId + SNAPSHOT_SEPARATOR + index;
    }
}
