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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 公共库写入的 scope 关口：visibility=public 需 public-ingest；否则 ingest。 */
class DocumentControllerPublicTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MultimodalRetrievalService> noMultimodal() {
        ObjectProvider<MultimodalRetrievalService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    @Test
    void publicWrite_withoutPublicIngestScope_isForbidden() {
        // 有 ingest 但没有 public-ingest → 写公共库应 403
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat", "ingest")));
        DocumentController controller = new DocumentController(
                mock(DocumentService.class), mock(DocumentTextExtractor.class), noMultimodal());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.uploadJson(Map.of(
                        "title", "refund.md", "text", "hello", "visibility", "public")));

        assertThat(ex.getStatusCode().value()).isEqualTo(403);
        assertThat(ex.getReason()).contains("public-ingest");
    }

    @Test
    void publicWrite_withPublicIngestScope_delegatesShared() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat", "public-ingest")));
        DocumentService service = mock(DocumentService.class);
        DocumentInfo info = new DocumentInfo("d1", "__public__", "refund.md", "text/plain",
                5, 1, 1, Instant.now(), "manual");
        when(service.upload("refund.md", "text/plain", "hello", "manual", -1, true)).thenReturn(info);
        DocumentController controller = new DocumentController(
                service, mock(DocumentTextExtractor.class), noMultimodal());

        var resp = controller.uploadJson(Map.of(
                "title", "refund.md", "text", "hello", "category", "manual", "visibility", "public"));

        assertThat(resp.getBody()).isEqualTo(info);
        verify(service).upload("refund.md", "text/plain", "hello", "manual", -1, true);
    }

    @Test
    void list_withPublicVisibility_readsSharedPartition_noSpecialScope() {
        // 普通登录用户（无 public-ingest）即可读共享文档元数据
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        DocumentService service = mock(DocumentService.class);
        DocumentInfo info = new DocumentInfo("d1", "__public__", "refund.md", "text/plain",
                5, 1, 1, Instant.now(), "manual");
        when(service.list(true)).thenReturn(List.of(info));
        DocumentController controller = new DocumentController(
                service, mock(DocumentTextExtractor.class), noMultimodal());

        assertThat(controller.list("public")).containsExactly(info);
        verify(service).list(true);
    }

    @Test
    void list_default_isTenantScoped() {
        DocumentService service = mock(DocumentService.class);
        when(service.list(false)).thenReturn(List.of());
        DocumentController controller = new DocumentController(
                service, mock(DocumentTextExtractor.class), noMultimodal());

        controller.list(null);
        verify(service).list(false);
    }

    @Test
    void get_withPublicVisibility_readsSharedPartition() {
        DocumentService service = mock(DocumentService.class);
        DocumentInfo info = new DocumentInfo("d1", "__public__", "refund.md", "text/plain",
                5, 1, 1, Instant.now(), "manual");
        when(service.get("d1", true)).thenReturn(Optional.of(info));
        DocumentController controller = new DocumentController(
                service, mock(DocumentTextExtractor.class), noMultimodal());

        var resp = controller.get("d1", "public");
        assertThat(resp.getBody()).isEqualTo(info);
        verify(service).get("d1", true);
    }

    @Test
    void publicDelete_withoutPublicIngestScope_isForbidden() {
        // 有 ingest 但没有 public-ingest → 删共享文档应 403
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat", "ingest")));
        DocumentController controller = new DocumentController(
                mock(DocumentService.class), mock(DocumentTextExtractor.class), noMultimodal());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.delete("d1", "public"));

        assertThat(ex.getStatusCode().value()).isEqualTo(403);
        assertThat(ex.getReason()).contains("public-ingest");
    }

    @Test
    void publicDelete_withPublicIngestScope_delegatesShared() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("public-ingest")));
        DocumentService service = mock(DocumentService.class);
        when(service.delete("d1", true)).thenReturn(true);
        DocumentController controller = new DocumentController(
                service, mock(DocumentTextExtractor.class), noMultimodal());

        var resp = controller.delete("d1", "public");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(service).delete("d1", true);
    }

    @Test
    void tenantDelete_withIngestScope_delegatesTenant() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        DocumentService service = mock(DocumentService.class);
        when(service.delete("d1", false)).thenReturn(true);
        DocumentController controller = new DocumentController(
                service, mock(DocumentTextExtractor.class), noMultimodal());

        controller.delete("d1", null);
        verify(service).delete("d1", false);
    }
}
