package com.threadscope.controller;

import com.threadscope.dto.*;
import com.threadscope.model.AnalysisResult;
import com.threadscope.service.AnalysisOrchestrator;
import com.threadscope.service.AnalysisStorageService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Dump 文件上传控制器。
 * 支持文件上传和文本粘贴两种输入方式。
 */
@RestController
@RequestMapping("/api/v1/dump")
public class DumpUploadController {

    private static final Logger log = LoggerFactory.getLogger(DumpUploadController.class);

    private final AnalysisOrchestrator orchestrator;
    private final AnalysisStorageService storageService;

    public DumpUploadController(AnalysisOrchestrator orchestrator, AnalysisStorageService storageService) {
        this.orchestrator = orchestrator;
        this.storageService = storageService;
    }

    /**
     * 文件上传分析。
     * POST /api/v1/dump/upload
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> uploadDump(@RequestParam("file") MultipartFile file) {
        log.info("Received dump file: {} ({}KB)", file.getOriginalFilename(), file.getSize() / 1024);

        try {
            String analysisId = UUID.randomUUID().toString();
            String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.txt";

            AnalysisResult result = orchestrator.analyze(analysisId, fileName, file.getInputStream());
            storageService.store(analysisId, result);

            return ResponseEntity.ok(new UploadResponse(
                result.analysisId(),
                result.fileName(),
                result.totalThreads(),
                result.parseTimeMs(),
                result.jvmVersion(),
                result.analyzedAt()
            ));
        } catch (Exception e) {
            log.error("Failed to analyze dump file", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 文本粘贴分析。
     * POST /api/v1/dump/paste
     */
    @PostMapping(value = "/paste", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UploadResponse> pasteDump(@RequestBody PasteRequest request) {
        if (request.content() == null || request.content().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        log.info("Received pasted dump content ({} chars)", request.content().length());

        try {
            String analysisId = UUID.randomUUID().toString();
            String fileName = "pasted-dump.txt";

            AnalysisResult result = orchestrator.analyzeFromText(analysisId, fileName, request.content());
            storageService.store(analysisId, result);

            return ResponseEntity.ok(new UploadResponse(
                result.analysisId(),
                result.fileName(),
                result.totalThreads(),
                result.parseTimeMs(),
                result.jvmVersion(),
                result.analyzedAt()
            ));
        } catch (Exception e) {
            log.error("Failed to analyze pasted content", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
