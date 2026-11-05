package com.threadscope.dto;

import java.time.Instant;

/**
 * 文件上传响应DTO。
 *
 * @param snapshotCount 本次上传切分出的快照数 (analysisId 指向最新一份)
 */
public record UploadResponse(
    String analysisId,
    String fileName,
    int totalThreads,
    long parseTimeMs,
    String jvmVersion,
    Instant analyzedAt,
    int snapshotCount
) {}
