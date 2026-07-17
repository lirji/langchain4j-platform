package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.DocumentService;
import com.lrj.platform.knowledge.lifecycle.DocumentTextExtractor;
import com.lrj.platform.knowledge.multimodal.MultimodalRetrievalService;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentControllerTest：验证 {@code DocumentController} 的上传接口——缺 ingest scope 时 403、具备 scope 时委托
 * {@link DocumentService}，以及图片上传在多模态关闭时返回 400、开启时经 {@link MultimodalRetrievalService} 入库为图片向量。
 */
class DocumentControllerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** 构造一个返回给定多模态服务（可为 null，表示未开启）的 ObjectProvider。 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<MultimodalRetrievalService> multimodalProvider(MultimodalRetrievalService mm) {
        ObjectProvider<MultimodalRetrievalService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mm);
        return provider;
    }

    @Test
    void uploadJson_withoutIngestScope_isForbidden() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        DocumentController controller = new DocumentController(
                mock(DocumentService.class),
                mock(DocumentTextExtractor.class),
                multimodalProvider(null));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.uploadJson(Map.of("title", "a.md", "text", "hello")));

        assertThat(ex.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void uploadJson_withIngestScope_delegates() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat", "ingest")));
        DocumentService service = mock(DocumentService.class);
        DocumentInfo info = new DocumentInfo("d1", "acme", "a.md", "text/markdown",
                5, 1, 1, Instant.now(), "manual");
        when(service.upload("a.md", "text/markdown", "hello", "manual")).thenReturn(info);
        DocumentController controller = new DocumentController(service, mock(DocumentTextExtractor.class), multimodalProvider(null));

        var response = controller.uploadJson(Map.of(
                "title", "a.md",
                "text", "hello",
                "contentType", "text/markdown",
                "category", "manual"));

        assertThat(response.getBody()).isEqualTo(info);
        verify(service).upload("a.md", "text/markdown", "hello", "manual");
    }

    @Test
    void uploadJsonImage_multimodalDisabled_returns400() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat", "ingest")));
        DocumentController controller = new DocumentController(
                mock(DocumentService.class),
                mock(DocumentTextExtractor.class),
                multimodalProvider(null));

        var response = controller.uploadJson(Map.of(
                "title", "chart.png",
                "imageBase64", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}),
                "contentType", "image/png"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().getFirst("X-Error")).contains("multimodal-embedding.enabled");
    }

    @Test
    void uploadJsonImage_multimodalEnabled_ingestsAsImageVector() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat", "ingest")));
        MultimodalRetrievalService mm = mock(MultimodalRetrievalService.class);
        when(mm.ingestImage(any(byte[].class), eq("image/png"), eq("chart.png"))).thenReturn("img-1");
        DocumentController controller = new DocumentController(
                mock(DocumentService.class),
                mock(DocumentTextExtractor.class),
                multimodalProvider(mm));

        var response = controller.uploadJson(Map.of(
                "title", "chart.png",
                "imageBase64", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}),
                "contentType", "image/png"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> bodyMap = (Map<String, Object>) response.getBody();
        assertThat(bodyMap)
                .containsEntry("id", "img-1")
                .containsEntry("fileName", "chart.png")
                .containsEntry("type", "image");
        verify(mm).ingestImage(any(byte[].class), eq("image/png"), eq("chart.png"));
    }
}
