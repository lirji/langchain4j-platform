package com.lrj.platform.knowledge.search;

import com.lrj.platform.knowledge.es.EsRagProperties;
import com.lrj.platform.knowledge.es.EsSearchHit;
import com.lrj.platform.knowledge.es.FakeEsGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EsKeywordRetrievalSourceTest {

    private final FakeEsGateway gateway = new FakeEsGateway();

    private static EsRagProperties props(boolean enabled, boolean queryEnabled, boolean normalize) {
        EsRagProperties p = new EsRagProperties();
        p.setEnabled(enabled);
        p.setQueryEnabled(queryEnabled);
        p.setNormalizeScore(normalize);
        return p;
    }

    private static RetrievalRequest request() {
        return new RetrievalRequest("退款政策", List.of("退款政策"), "acme", null, 5, 0.0);
    }

    @Test
    void enabled_reflectsQueryActive() {
        assertThat(new EsKeywordRetrievalSource(gateway, props(true, true, true), 1.0).enabled()).isTrue();
        assertThat(new EsKeywordRetrievalSource(gateway, props(true, false, true), 1.0).enabled()).isFalse();
        assertThat(new EsKeywordRetrievalSource(gateway, props(false, true, true), 1.0).enabled()).isFalse();
    }

    @Test
    void retrieve_normalizesBm25AndSetsMergeKey() {
        gateway.searchResult = List.of(
                new EsSearchHit("d1", "a.md", "manual", "0", "1", "退款政策文本", 10.0),
                new EsSearchHit("d2", "b.md", "manual", "3", "1", "另一段", 5.0));
        var source = new EsKeywordRetrievalSource(gateway, props(true, true, true), 1.0);

        List<RetrievalHit> hits = source.retrieve(request());

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).score()).isEqualTo(1.0);      // 10/10
        assertThat(hits.get(1).score()).isEqualTo(0.5);      // 5/10
        assertThat(hits.get(0).mergeKey()).isEqualTo("d1#0"); // 与向量/关键词对齐
        assertThat(hits.get(0).id()).isEqualTo("es:d1#0");
        assertThat(hits.get(0).source()).isEqualTo("es");
    }

    @Test
    void retrieve_appliesEsWeight() {
        gateway.searchResult = List.of(new EsSearchHit("d1", "a.md", "manual", "0", "1", "t", 10.0));
        var source = new EsKeywordRetrievalSource(gateway, props(true, true, true), 2.0);
        assertThat(source.retrieve(request()).get(0).score()).isEqualTo(2.0); // (10/10)*2
    }

    @Test
    void retrieve_keepsRawScoreWhenNormalizeOff() {
        gateway.searchResult = List.of(new EsSearchHit("d1", "a.md", "manual", "0", "1", "t", 7.5));
        var source = new EsKeywordRetrievalSource(gateway, props(true, true, false), 1.0);
        assertThat(source.retrieve(request()).get(0).score()).isEqualTo(7.5);
    }

    @Test
    void retrieve_degradesToEmptyOnError() {
        gateway.searchError = new IllegalStateException("es down");
        var source = new EsKeywordRetrievalSource(gateway, props(true, true, true), 1.0);
        assertThat(source.retrieve(request())).isEmpty();
    }

    @Test
    void retrieve_passesTenantAndCategoryToGateway() {
        // #9：ES 源必须把租户与 category 下推到网关（真实隔离在 ElasticsearchEsGateway 的 term filter，由 smoke 验证）。
        var source = new EsKeywordRetrievalSource(gateway, props(true, true, true), 1.0);
        var req = new RetrievalRequest("退款", List.of("退款"), "acme", "客服", 7, 0.0);
        source.retrieve(req);
        assertThat(gateway.lastSearchTenant).isEqualTo("acme");
        assertThat(gateway.lastSearchCategory).isEqualTo("客服");
        assertThat(gateway.lastSearchLimit).isEqualTo(7);
    }
}
