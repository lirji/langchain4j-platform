package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.ingest.job.IngestionJobNotFoundException;
import com.lrj.platform.knowledge.store.DimensionMismatchException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 锁定 {@link KnowledgeExceptionHandler} 的兜底契约（standalone MockMvc，不启动 Spring context）：
 * controller 主动抛出的状态语义（403）原样保留、非法入参→400、维度冲突→409、其余未捕获异常→结构化 500
 * （不再是无日志、无结构的裸 500——这正是删除文档时后端存储报错暴露给前端的老问题）。
 */
class KnowledgeExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ThrowingController())
            .setControllerAdvice(new KnowledgeExceptionHandler())
            .build();

    @Test
    void responseStatusException_preservesStatus() throws Exception {
        mvc.perform(get("/t/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("request_failed"))
                .andExpect(jsonPath("$.message").value("ingest scope required"));
    }

    @Test
    void illegalArgument_mapsToBadRequest() throws Exception {
        mvc.perform(get("/t/bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value("title is required"));
    }

    @Test
    void dimensionMismatch_mapsToConflict() throws Exception {
        mvc.perform(get("/t/dim"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("dimension_mismatch"));
    }

    @Test
    void unexpectedRuntimeException_mapsToStructured500NotBare() throws Exception {
        mvc.perform(get("/t/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("internal_error"))
                .andExpect(jsonPath("$.message").value("internal server error"));
    }

    @Test
    void missingIngestionJob_mapsToTenantSafeNotFound() throws Exception {
        mvc.perform(get("/t/missing-job"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/t/forbidden")
        String forbidden() {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ingest scope required");
        }

        @GetMapping("/t/bad")
        String bad() {
            throw new IllegalArgumentException("title is required");
        }

        @GetMapping("/t/dim")
        String dim() {
            throw new DimensionMismatchException("knowledge_segments_acme", 768, 64);
        }

        @GetMapping("/t/boom")
        String boom() {
            throw new RuntimeException("simulated qdrant delete failure");
        }

        @GetMapping("/t/missing-job")
        String missingJob() {
            throw new IngestionJobNotFoundException("job-1");
        }
    }
}
