package com.threadscope.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * top -H 输出关联请求。
 */
public record TopCorrelationRequest(
    @NotBlank(message = "topOutput must not be blank")
    @Size(max = 2_000_000, message = "topOutput exceeds the 2MB size limit")
    String topOutput
) {}
