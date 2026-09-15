package com.lrj.platform.knowledge;

import com.lrj.platform.knowledge.graph.GraphSearchService;
import com.lrj.platform.knowledge.hybrid.KeywordSearchService;
import com.lrj.platform.knowledge.search.RetrievalSource;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 通过四路共同屏障验证真实编排重叠执行，同时检查依赖 ThreadLocal 的源拿到完整身份。 */
class KnowledgeQueryServiceParallelTest {
    @AfterEach
    void clearContext() {
        TenantContext.clear();
        MDC.clear();
    }

    @Test
    void allFourSourcesOverlapWithIdentityAndLoggingContext() {
        var tenant = new TenantContext.Tenant("acme", "alice", Set.of("chat"), "acme_sales");
        TenantContext.set(tenant);
        MDC.put("traceId", "trace-acme");
        var barrier = new CountDownLatch(4);
        Runnable enter = () -> {
            assertThat(TenantContext.current()).isEqualTo(tenant);
            assertThat(MDC.get("traceId")).isEqualTo("trace-acme");
            barrier.countDown();
            await(barrier);
        };
        var realModel = new KnowledgeEmbeddingConfig.HashEmbeddingModel();
        var model = mock(EmbeddingModel.class);
        when(model.dimension()).thenReturn(realModel.dimension());
        when(model.embed(anyString())).thenAnswer(inv -> {
            enter.run();
            return realModel.embed(inv.<String>getArgument(0));
        });
        var keyword = mock(KeywordSearchService.class);
        when(keyword.search(anyString(), anyInt(), isNull(), isNull())).thenAnswer(inv -> {
            enter.run();
            return List.of();
        });
        var graph = mock(GraphSearchService.class);
        when(graph.query(anyString(), isNull(), anyInt(), isNull())).thenAnswer(inv -> {
            enter.run();
            return new GraphSearchService.GraphQueryResult("query", "acme", Set.of(), List.of());
        });
        var es = mock(RetrievalSource.class);
        when(es.enabled()).thenReturn(true);
        when(es.retrieve(any())).thenAnswer(inv -> {
            enter.run();
            return List.of();
        });
        var disabled = mock(RetrievalSource.class);
        var service = new KnowledgeQueryService(new InMemoryEmbeddingStore<>(), model, keyword,
                5, 0, true, 5, graph, true, 5);
        try {
            service.setExtraSources(List.of(es, disabled));
            assertThat(service.query("query", 5, 0.0, null).tenantId()).isEqualTo("acme");
            verify(disabled, never()).retrieve(any());
            assertThat(TenantContext.current()).isEqualTo(tenant);
            assertThat(MDC.get("traceId")).isEqualTo("trace-acme");
        } finally {
            service.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
