package com.threadscope.dto;

import java.time.Instant;

/**
 * 文件上传响应DTO。
 */
public record UploadResponse(
    String analysisId,
    String fileName,
    int totalThreads,
    long parseTimeMs,
    String jvmVersion,
    Instant analyzedAt
) {}
