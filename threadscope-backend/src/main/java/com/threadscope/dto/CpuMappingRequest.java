package com.threadscope.dto;

/**
 * CPU映射请求 — 接收 top -H 命令输出。
 */
public record CpuMappingRequest(
    String topHOutput
) {}
