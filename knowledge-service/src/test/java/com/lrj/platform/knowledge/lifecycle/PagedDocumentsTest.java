package com.lrj.platform.knowledge.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PagedDocuments} 的 JSON 信封契约：字段名恰为 {@code items/page/size/total/totalPages} 且值正确，
 * 防止字段重命名/遗漏/类型映射回归。只验分页信封本身，不掺 {@link DocumentInfo} 的时间序列化配置。
 */
class PagedDocumentsTest {

    @Test
    void serializesStableEnvelopeFieldNamesAndValues() {
        PagedDocuments envelope = new PagedDocuments(List.of(), 2, 20, 21L, 2);

        JsonNode json = new ObjectMapper().valueToTree(envelope);
        Set<String> fields = new LinkedHashSet<>();
        json.fieldNames().forEachRemaining(fields::add);

        assertThat(fields).containsExactlyInAnyOrder(
                "items", "page", "size", "total", "totalPages");
        assertThat(json.get("items").isArray()).isTrue();
        assertThat(json.get("items").isEmpty()).isTrue();
        assertThat(json.get("page").asInt()).isEqualTo(2);
        assertThat(json.get("size").asInt()).isEqualTo(20);
        assertThat(json.get("total").asLong()).isEqualTo(21L);
        assertThat(json.get("totalPages").asInt()).isEqualTo(2);
    }
}
