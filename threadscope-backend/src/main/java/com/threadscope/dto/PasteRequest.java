package com.threadscope.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 文本粘贴请求DTO。
 * 内容上限约 50MB 字符 — 与文件上传上限保持一致，防止无界输入撑爆堆内存。
 */
public record PasteRequest(
    @NotBlank(message = "content must not be blank")
    @Size(max = 52_428_800, message = "content exceeds the 50MB size limit")
    String content
) {}
