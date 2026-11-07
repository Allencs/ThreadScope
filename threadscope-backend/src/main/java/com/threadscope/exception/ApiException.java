package com.threadscope.exception;

import org.springframework.http.HttpStatus;

/** 需要指定状态码和错误码的业务异常，例如登录失败、权限不足。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
