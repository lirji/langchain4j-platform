package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.DocumentService;
import com.lrj.platform.knowledge.lifecycle.DocumentTextExtractor;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentControllerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void uploadJson_withoutIngestScope_isForbidden() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        DocumentController controller = new DocumentController(mock(DocumentService.class), mock(DocumentTextExtractor.class));

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
        DocumentController controller = new DocumentController(service, mock(DocumentTextExtractor.class));

        var response = controller.uploadJson(Map.of(
                "title", "a.md",
                "text", "hello",
                "contentType", "text/markdown",
                "category", "manual"));

        assertThat(response.getBody()).isEqualTo(info);
        verify(service).upload("a.md", "text/markdown", "hello", "manual");
    }

    @Test
    void uploadJsonImage_usesCaptionAndOcrAsIndexText() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat", "ingest")));
        DocumentService service = mock(DocumentService.class);
        DocumentInfo info = new DocumentInfo("img1", "acme", "chart.png", "image/png",
                3, 1, 1, Instant.now(), "report");
        when(service.upload("chart.png", "image/png",
                "Image caption:\n退款趋势图\n\nImage OCR:\nMay refund 99",
                "report",
                3)).thenReturn(info);
        DocumentController controller = new DocumentController(service, mock(DocumentTextExtractor.class));

        var response = controller.uploadJson(Map.of(
                "title", "chart.png",
                "imageBase64", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}),
                "contentType", "image/png",
                "caption", "退款趋势图",
                "ocrText", "May refund 99",
                "category", "report"));

        assertThat(response.getBody()).isEqualTo(info);
        verify(service).upload("chart.png", "image/png",
                "Image caption:\n退款趋势图\n\nImage OCR:\nMay refund 99",
                "report",
                3);
    }

    @Test
    void uploadJsonImage_requiresCaptionOrOcrText() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat", "ingest")));
        DocumentController controller = new DocumentController(mock(DocumentService.class), mock(DocumentTextExtractor.class));

        var response = controller.uploadJson(Map.of(
                "title", "chart.png",
                "imageBase64", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().getFirst("X-Error")).contains("caption or ocrText");
    }
}
