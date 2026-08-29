package com.threadscope.exception;

/**
 * Dump 超出允许的规模（线程数或字符数）— 映射为 HTTP 413。
 */
public class DumpTooLargeException extends RuntimeException {

    public DumpTooLargeException(String message) {
        super(message);
    }
}
