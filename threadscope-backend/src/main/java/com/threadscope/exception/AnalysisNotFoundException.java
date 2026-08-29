package com.threadscope.exception;

/**
 * 指定的 analysisId 不存在或已过期 — 映射为 HTTP 404。
 */
public class AnalysisNotFoundException extends RuntimeException {

    public AnalysisNotFoundException(String analysisId) {
        super("Analysis not found: " + analysisId + ". It may have expired.");
    }
}
