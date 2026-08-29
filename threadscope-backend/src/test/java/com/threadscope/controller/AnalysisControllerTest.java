package com.threadscope.controller;

import com.threadscope.exception.AnalysisNotFoundException;
import com.threadscope.model.*;
import com.threadscope.service.AnalysisStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AnalysisController.class)
class AnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalysisStorageService storageService;

    private static AnalysisResult resultWithThreads(int count) {
        List<ThreadInfo> threads = IntStream.range(0, count)
            .mapToObj(i -> new ThreadInfo("worker-" + i, i, false, 5, 0, null, null,
                "0x" + i, "0x" + i, i, ThreadState.RUNNABLE, null, null,
                List.of(), List.of(), List.of()))
            .toList();
        return new AnalysisResult("test-id", "test.txt", "21", Instant.now(), 10,
            threads, Map.of(ThreadState.RUNNABLE, (long) count),
            new DeadlockInfo(List.of()), List.of(), List.of(), List.of(), List.of(), null, null);
    }

    @Test
    void unknownAnalysisIdReturns404() throws Exception {
        when(storageService.getOrThrow(anyString())).thenThrow(new AnalysisNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/analysis/missing/overview"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
    }

    @Test
    void pageZeroDoesNotCrash() throws Exception {
        when(storageService.getOrThrow(anyString())).thenReturn(resultWithThreads(10));

        mockMvc.perform(get("/api/v1/analysis/test-id/threads")
                .param("page", "0").param("size", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.threads.length()").value(5));
    }

    @Test
    void negativePageAndOversizedPageSizeAreClamped() throws Exception {
        when(storageService.getOrThrow(anyString())).thenReturn(resultWithThreads(3));

        mockMvc.perform(get("/api/v1/analysis/test-id/threads")
                .param("page", "-5").param("size", "999999"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.threads.length()").value(3));
    }

    @Test
    void pageBeyondEndReturnsEmptyList() throws Exception {
        when(storageService.getOrThrow(anyString())).thenReturn(resultWithThreads(3));

        mockMvc.perform(get("/api/v1/analysis/test-id/threads")
                .param("page", "100").param("size", "50"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.threads.length()").value(0));
    }

    @Test
    void stateFilterNarrowsResults() throws Exception {
        when(storageService.getOrThrow(anyString())).thenReturn(resultWithThreads(4));

        mockMvc.perform(get("/api/v1/analysis/test-id/threads").param("state", "BLOCKED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));
    }
}
