package com.threadscope.controller;

import com.threadscope.dto.*;
import com.threadscope.model.AnalysisResult;
import com.threadscope.service.AnalysisOrchestrator;
import com.threadscope.service.AnalysisStorageService;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
     * 文件上传分析。支持一次上传多个 dump 文件（同名 file 字段重复）：
     * 多个文件按文件名排序视为按时间先后抓取的快照，自动做差分对比；
     * 单个文件内含多个 "Full thread dump" 段时同样会切分对比。
     * POST /api/v1/dump/upload
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> uploadDump(@RequestParam("file") List<MultipartFile> files) throws IOException {
        List<MultipartFile> valid = files.stream().filter(f -> !f.isEmpty()).toList();
        if (valid.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }

        // 多文件按名字典序 = 抓取顺序 (jstack 输出常按时间命名)
        List<MultipartFile> sorted = valid.stream()
            .sorted(java.util.Comparator.comparing(
                f -> f.getOriginalFilename() != null ? f.getOriginalFilename() : ""))
            .toList();

        long totalKb = sorted.stream().mapToLong(MultipartFile::getSize).sum() / 1024;
        log.info("Received {} dump file(s), total {}KB", sorted.size(), totalKb);

        String analysisId = UUID.randomUUID().toString();
        String fileName = sorted.size() == 1
            ? (sorted.getFirst().getOriginalFilename() != null ? sorted.getFirst().getOriginalFilename() : "unknown.txt")
            : sorted.stream()
                .map(f -> f.getOriginalFilename() != null ? f.getOriginalFilename() : "unknown.txt")
                .collect(Collectors.joining(", "));

        List<String> contents = new ArrayList<>();
        for (MultipartFile file : sorted) {
            contents.add(new String(file.getBytes(), StandardCharsets.UTF_8));
        }

        AnalysisResult result = orchestrator.analyzeContents(analysisId, fileName, contents);
        storageService.store(analysisId, result);

        return ResponseEntity.ok(toResponse(result));
    }

    /**
     * 文本粘贴分析。
     * POST /api/v1/dump/paste
     */
    @PostMapping(value = "/paste", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UploadResponse> pasteDump(@Valid @RequestBody PasteRequest request) throws IOException {
        log.info("Received pasted dump content ({} chars)", request.content().length());

        String analysisId = UUID.randomUUID().toString();
        String fileName = "pasted-dump.txt";

        AnalysisResult result = orchestrator.analyzeFromText(analysisId, fileName, request.content());
        storageService.store(analysisId, result);

        return ResponseEntity.ok(toResponse(result));
    }

    private static UploadResponse toResponse(AnalysisResult result) {
        return new UploadResponse(
            result.analysisId(),
            result.fileName(),
            result.totalThreads(),
            result.parseTimeMs(),
            result.jvmVersion(),
            result.analyzedAt()
        );
    }
}
