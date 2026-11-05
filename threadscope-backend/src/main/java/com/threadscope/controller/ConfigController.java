package com.threadscope.controller;

import com.threadscope.config.AnalysisProperties;
import com.threadscope.engine.analyzer.CodeLayerClassifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 前端所需的分析规则配置 — 保证前后端对"JDK / 框架 / 业务代码"的划分一致。
 */
@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    private final AnalysisProperties properties;

    public ConfigController(AnalysisProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/code-layers")
    public ResponseEntity<Map<String, Object>> codeLayers() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jdkPrefixes", CodeLayerClassifier.JDK_PREFIXES);
        body.put("frameworkPrefixes", CodeLayerClassifier.FRAMEWORK_PREFIXES);
        body.put("defaultBusinessPackages", properties.businessPackages());
        return ResponseEntity.ok(body);
    }
}
