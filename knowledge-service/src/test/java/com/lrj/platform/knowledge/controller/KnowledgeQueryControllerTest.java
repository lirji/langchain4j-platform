package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.KnowledgeQueryService;
import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeQueryControllerTest {

    @Test
    void query_delegatesToService() {
        KnowledgeQueryService service = mock(KnowledgeQueryService.class);
        var result = new KnowledgeQueryService.QueryResult("refund", "acme", List.of());
        when(service.query("refund", 3, 0.2, "manual")).thenReturn(result);
        KnowledgeQueryController controller = new KnowledgeQueryController(service);

        var response = controller.query(new KnowledgeQueryRequest("refund", 3, 0.2, "manual"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().query()).isEqualTo(result.query());
        assertThat(response.getBody().tenantId()).isEqualTo(result.tenantId());
        verify(service).query("refund", 3, 0.2, "manual");
    }

    @Test
    void query_mapsVisibilityFromSharedFlag() {
        KnowledgeQueryService service = mock(KnowledgeQueryService.class);
        var tenantHit = new KnowledgeQueryService.Hit("t#0", 0.9, "t", "t.md", "manual", "0", "私有", "vector", false);
        var publicHit = new KnowledgeQueryService.Hit("p#0", 0.8, "p", "p.md", "manual", "0", "共享", "vector", true);
        when(service.query("q", null, null, null))
                .thenReturn(new KnowledgeQueryService.QueryResult("q", "acme", List.of(tenantHit, publicHit)));
        KnowledgeQueryController controller = new KnowledgeQueryController(service);

        var body = controller.query(new KnowledgeQueryRequest("q", null, null, null)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.hits()).extracting(KnowledgeHit::visibility).containsExactly("tenant", "public");
    }

    @Test
    void config_reflectsRuntimeSharedState() {
        KnowledgeQueryService service = mock(KnowledgeQueryService.class);
        when(service.publicKbEnabled()).thenReturn(true);
        KnowledgeQueryController controller = new KnowledgeQueryController(service);

        var view = controller.config();

        assertThat(view.contractVersion()).isEqualTo(1);
        assertThat(view.publicEnabled()).isTrue();
        assertThat(view.sharedImagesSupported()).isFalse(); // 共享图片当前不支持
    }

    @Test
    void query_returnsBadRequestForBlankQuery() {
        KnowledgeQueryService service = mock(KnowledgeQueryService.class);
        when(service.query(" ", null, null, null)).thenThrow(new IllegalArgumentException("query is required"));
        KnowledgeQueryController controller = new KnowledgeQueryController(service);

        var response = controller.query(new KnowledgeQueryRequest(" ", null, null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
