package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.KnowledgeQueryService;
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
    void query_returnsBadRequestForBlankQuery() {
        KnowledgeQueryService service = mock(KnowledgeQueryService.class);
        when(service.query(" ", null, null, null)).thenThrow(new IllegalArgumentException("query is required"));
        KnowledgeQueryController controller = new KnowledgeQueryController(service);

        var response = controller.query(new KnowledgeQueryRequest(" ", null, null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
