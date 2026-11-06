package com.threadscope.controller;

import com.threadscope.dto.*;
import com.threadscope.engine.parser.DumpTextDecoder;
import com.threadscope.exception.DumpTooLargeException;
import com.threadscope.model.AnalysisBundle;
import com.threadscope.model.AnalysisResult;
import com.threadscope.service.AnalysisOrchestrator;
import com.threadscope.service.AnalysisOrchestrator.AnalysisOptions;
import com.threadscope.service.AnalysisOrchestrator.DumpSource;
import com.threadscope.service.AnalysisStorageService;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dump 文件上传控制器。
 * 支持文件上传和文本粘贴两种输入方式。
 */
@RestController
@RequestMapping("/api/v1/dump")
public class DumpUploadController {

    private static final Logger log = LoggerFactory.getLogger(DumpUploadController.class);
    private static final int MAX_BUSINESS_PACKAGES = 50;
    private static final long MAX_DUMP_BYTES = 50L * 1024 * 1024;

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
     *
     * @param charset          文件编码，默认自动探测 (BOM / UTF-16 / UTF-8 / GB18030)
     * @param businessPackages 业务包前缀，逗号或换行分隔
     * @param applicationName  应用名
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> uploadDump(
            @RequestParam("file") List<MultipartFile> files,
            @RequestParam(required = false) String charset,
            @RequestParam(required = false) String businessPackages,
            @RequestParam(required = false) String applicationName) throws IOException {
        List<MultipartFile> valid = files.stream().filter(f -> !f.isEmpty()).toList();
        if (valid.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        long totalBytes = valid.stream().mapToLong(MultipartFile::getSize).sum();
        if (totalBytes > MAX_DUMP_BYTES) {
            throw new DumpTooLargeException("Thread dump upload exceeds the 50MB limit");
        }

        // 多文件按名字典序 = 抓取顺序 (jstack 输出常按时间命名)
        List<MultipartFile> sorted = valid.stream()
            .sorted(Comparator.comparing(
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

        List<DumpSource> sources = new ArrayList<>();
        for (MultipartFile file : sorted) {
            DumpTextDecoder.Decoded decoded = decode(file.getBytes(), charset);
            String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.txt";
            sources.add(new DumpSource(name, decoded.text(), decoded.charset(), decoded.fallback()));
        }

        AnalysisBundle bundle = orchestrator.analyzeBundle(analysisId, fileName, sources,
            new AnalysisOptions(splitPackages(businessPackages), trimToNull(applicationName)));
        storageService.store(bundle);

        return ResponseEntity.ok(toResponse(bundle));
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

        List<String> packages = request.businessPackages() == null ? List.of()
            : sanitizePackages(request.businessPackages());
        AnalysisBundle bundle = orchestrator.analyzeBundle(analysisId, fileName,
            List.of(DumpSource.utf8(fileName, request.content())),
            new AnalysisOptions(packages, trimToNull(request.applicationName())));
        storageService.store(bundle);

        return ResponseEntity.ok(toResponse(bundle));
    }

    private static DumpTextDecoder.Decoded decode(byte[] bytes, String charset) {
        try {
            return DumpTextDecoder.decode(bytes, charset);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new IllegalArgumentException("Unsupported charset: " + charset);
        }
    }

    static List<String> splitPackages(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return sanitizePackages(Arrays.asList(raw.split("[,;\\s]+")));
    }

    private static List<String> sanitizePackages(List<String> packages) {
        return packages.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isEmpty() && s.matches("[\\w.$]+"))
            .distinct()
            .limit(MAX_BUSINESS_PACKAGES)
            .toList();
    }

    private static String trimToNull(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > 100 ? t.substring(0, 100) : t;
    }

    private static UploadResponse toResponse(AnalysisBundle bundle) {
        AnalysisResult result = bundle.latest();
        return new UploadResponse(
            bundle.analysisId(),
            result.fileName(),
            result.totalThreads(),
            result.parseTimeMs(),
            result.jvmVersion(),
            result.analyzedAt(),
            bundle.snapshots().size()
        );
    }
}
