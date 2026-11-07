package com.threadscope.jfr;

import com.threadscope.auth.AuthUser;
import com.threadscope.exception.AnalysisNotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/jfr")
@Profile("!test")
public class JfrController {

    private final JfrAnalysisService service;

    public JfrController(JfrAnalysisService service) {
        this.service = service;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JfrDocuments.ReportSummary upload(@RequestParam("file") MultipartFile file,
                                             @AuthenticationPrincipal AuthUser user) throws IOException {
        return service.upload(file, user.getUsername());
    }

    @GetMapping
    public List<JfrDocuments.ReportSummary> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public JfrDocuments.ReportSummary get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/{id}/hotspots")
    public JfrDocuments.HotspotList hotspots(@PathVariable String id,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "self") String sort,
                                             @RequestParam(required = false) Integer limit,
                                             @RequestParam(required = false) Integer from,
                                             @RequestParam(required = false) Integer to) {
        return service.hotspots(id, q, sort, limit, from, to);
    }

    @GetMapping("/{id}/cpu")
    public ResponseEntity<JfrDocuments.CpuTimeline> cpu(@PathVariable String id) {
        JfrDocuments.CpuTimeline timeline = service.cpu(id);
        return timeline == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(timeline);
    }

    /** path 从热点根方法开始，到要展开的节点为止。 */
    @PostMapping("/{id}/hotspots/callers")
    public List<JfrDocuments.HotspotCaller> callers(@PathVariable String id, @RequestBody CallersRequest request) {
        return service.callers(id, request.path(), request.basis(), request.q(), request.from(), request.to());
    }

    public record CallersRequest(List<String> path, String basis, String q, Integer from, Integer to) {}

    @GetMapping("/{id}/memory")
    public ResponseEntity<JfrDocuments.MemoryOverview> memory(@PathVariable String id) {
        JfrDocuments.MemoryOverview overview = service.memory(id);
        return overview == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(overview);
    }

    /** sort：self / inclusive / business；pkgs 是业务包前缀，逗号分隔。 */
    @GetMapping("/{id}/memory/allocations")
    public JfrDocuments.AllocationList allocations(@PathVariable String id,
                                                   @RequestParam(required = false) String source,
                                                   @RequestParam(required = false) String q,
                                                   @RequestParam(defaultValue = "business") String sort,
                                                   @RequestParam(required = false) Integer limit,
                                                   @RequestParam(required = false) Integer from,
                                                   @RequestParam(required = false) Integer to,
                                                   @RequestParam(required = false) String type,
                                                   @RequestParam(required = false) List<String> pkgs) {
        return service.allocations(id, source, q, sort, limit, from, to, type, pkgs);
    }

    @GetMapping("/{id}/memory/stalls")
    public JfrDocuments.StallReport stalls(@PathVariable String id,
                                           @RequestParam(defaultValue = "business") String group,
                                           @RequestParam(required = false) String thread,
                                           @RequestParam(required = false) Integer from,
                                           @RequestParam(required = false) Integer to,
                                           @RequestParam(required = false) List<String> pkgs) {
        return service.stalls(id, group, thread, from, to, pkgs);
    }

    @PostMapping("/{id}/memory/allocations/callers")
    public List<JfrDocuments.HotspotCaller> allocationCallers(@PathVariable String id,
                                                              @RequestBody AllocationCallersRequest request) {
        return service.allocationCallers(id, request.source(), request.path(), request.basis(), request.q(),
            request.from(), request.to(), request.type(), request.pkgs());
    }

    public record AllocationCallersRequest(String source, List<String> path, String basis, String q,
                                           Integer from, Integer to, String type, List<String> pkgs) {}

    @GetMapping("/{id}/threads")
    public JfrDocuments.ThreadOverview threads(@PathVariable String id) {
        return service.threads(id);
    }

    @GetMapping("/{id}/threads/{javaThreadId}")
    public JfrDocuments.PlatformThread thread(@PathVariable String id, @PathVariable long javaThreadId) {
        return service.thread(id, javaThreadId);
    }

    @PostMapping("/{id}/open-dump")
    public JfrAnalysisService.OpenDump openDump(@PathVariable String id) {
        return service.openDump(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @AuthenticationPrincipal AuthUser user) {
        service.delete(id, user.getUsername(), user.isAdmin());
        return ResponseEntity.noContent().build();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(AnalysisNotFoundException.class)
    public ResponseEntity<com.threadscope.exception.GlobalExceptionHandler.ErrorResponse> missing(AnalysisNotFoundException e) {
        return ResponseEntity.status(404)
            .body(new com.threadscope.exception.GlobalExceptionHandler.ErrorResponse("ANALYSIS_NOT_FOUND", e.getMessage()));
    }
}
