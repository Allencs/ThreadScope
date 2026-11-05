package com.threadscope.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 文本粘贴请求DTO。
 * 内容上限约 50MB 字符 — 与文件上传上限保持一致，防止无界输入撑爆堆内存。
 *
 * @param businessPackages 业务包前缀 (可选)，用于区分业务代码与框架代码
 * @param applicationName  应用名 (可选)
 */
public record PasteRequest(
    @NotBlank(message = "content must not be blank")
    @Size(max = 52_428_800, message = "content exceeds the 50MB size limit")
    String content,
    @Size(max = 50, message = "at most 50 business packages")
    List<@Size(max = 200) String> businessPackages,
    @Size(max = 100) String applicationName
) {}
