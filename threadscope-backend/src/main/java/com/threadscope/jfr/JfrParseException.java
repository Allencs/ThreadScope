package com.threadscope.jfr;

/**
 * .jfr 不是合法的录制文件，或解析过程无法继续。
 */
public class JfrParseException extends RuntimeException {

    public JfrParseException(String message) {
        super(message);
    }

    public JfrParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
