package com.lrj.platform.knowledge.es;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ES 查询体安全单测：租户 term <strong>恒在</strong>、category 可选、空 tenant fail-fast。
 * 直接断言 {@link ElasticsearchEsGateway#buildSearchBody} 生成的 JSON，无需真实 ES。
 */
class ElasticsearchEsGatewayTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode filters(ObjectNode body) {
        return body.path("query").path("bool").path("filter");
    }

    @Test
    void alwaysIncludesTenantTermAsFirstFilter() {
        ObjectNode body = ElasticsearchEsGateway.buildSearchBody(M, "acme", null, "退款", 10);
        JsonNode filter = filters(body);
        assertTrue(filter.isArray());
        assertEquals("acme", filter.path(0).path("term").path("tenantId").asText());
    }

    @Test
    void addsCategoryTermWhenPresent() {
        ObjectNode body = ElasticsearchEsGateway.buildSearchBody(M, "acme", "客服", "退款", 10);
        JsonNode filter = filters(body);
        assertEquals(2, filter.size());
        assertEquals("acme", filter.path(0).path("term").path("tenantId").asText());
        assertEquals("客服", filter.path(1).path("term").path("category").asText());
    }

    @Test
    void omitsCategoryTermWhenBlank() {
        JsonNode filter = filters(ElasticsearchEsGateway.buildSearchBody(M, "acme", "  ", "q", 10));
        assertEquals(1, filter.size());
        assertEquals("acme", filter.path(0).path("term").path("tenantId").asText());
    }

    @Test
    void sizeIsAtLeastOne() {
        assertEquals(1, ElasticsearchEsGateway.buildSearchBody(M, "acme", null, "q", 0).path("size").asInt());
        assertEquals(7, ElasticsearchEsGateway.buildSearchBody(M, "acme", null, "q", 7).path("size").asInt());
    }

    @Test
    void failsFastOnBlankTenant() {
        assertThrows(IllegalArgumentException.class, () -> ElasticsearchEsGateway.buildSearchBody(M, "", null, "q", 10));
        assertThrows(IllegalArgumentException.class, () -> ElasticsearchEsGateway.buildSearchBody(M, null, null, "q", 10));
        assertThrows(IllegalArgumentException.class, () -> ElasticsearchEsGateway.requireTenant("  "));
    }
}
