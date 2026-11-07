package com.threadscope.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threadscope.exception.GlobalExceptionHandler.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 过滤器里没有 ControllerAdvice，按同样的错误体格式直接写响应。 */
final class ApiErrorWriter {

    private final ObjectMapper mapper;

    ApiErrorWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), new ErrorResponse(code, message));
    }
}
