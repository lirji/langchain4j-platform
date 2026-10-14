package com.lrj.platform.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.knowledge.authz.NoopKnowledgeAuthz;
import com.lrj.platform.knowledge.es.NoopSegmentIndexer;
import com.lrj.platform.knowledge.hybrid.KeywordSearchService;
import com.lrj.platform.knowledge.hybrid.SimpleKeywordTokenizer;
import com.lrj.platform.knowledge.ingest.NoopContextualEnricher;
import com.lrj.platform.knowledge.ingest.job.*;
import com.lrj.platform.knowledge.ingest.sync.*;
import com.lrj.platform.knowledge.lifecycle.*;
import com.lrj.platform.knowledge.store.InMemoryEmbeddingStoreRouter;
import com.lrj.platform.security.TenantContext;
import com.lrj.platform.protocol.knowledge.KnowledgeSourceFeed.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

/** HTTP真实请求→关系库检查点→原文解析→worker→检索；使用离线HashEmbedding，不调用付费模型。 */
class KnowledgeHttpSyncPipelineTest {
    @Test void httpFeedCreatesUpdatesAndRetiresRetrievableKnowledge() throws Exception {
        var json = new ObjectMapper(); var clock = new SyncTestSupport.MutableClock();
        var db = SyncTestSupport.database();
        var checkpoint = new KnowledgeSyncStore(db, json, clock);
        var jobs = new JdbcIngestionJobStore(db, json);
        var registry = new InMemoryDocumentRegistry(); var router = new InMemoryEmbeddingStoreRouter();
        var originals = new InMemoryDocumentSourceStore(); var auth = new NoopKnowledgeAuthz();
        var submissions = new IngestionSubmissionService(originals, jobs, registry, auth, clock, Set.of(IngestionSink.VECTOR, IngestionSink.REGISTRY));
        var service = new KnowledgeSyncService(checkpoint, new HttpKnowledgeSource(json), submissions, registry, auth, new com.lrj.platform.audit.AuditLogger(json));
        var feed = new AtomicReference<>(new Page(List.of(new Item("faq", 1, "Refund", "refund within three days", false)), "c1"));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed", exchange -> {
            byte[] bytes = json.writeValueAsBytes(feed.get()); exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        }); server.start();
        var source = new KnowledgeSyncProperties.Source("faq", "pipeline-" + UUID.randomUUID(), "sync-user", "support",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/feed"), null, true);
        var model = new KnowledgeEmbeddingConfig.HashEmbeddingModel();
        var splitter = new DocumentSplitterFactory("recursive", "chars", 300, 0, 0, "gpt-4o-mini", "recursive", 300, 0, 1, 95, 200, 0, model);
        try (var worker = new IngestionJobWorker(jobs, new DefaultIngestionDocumentPreparer(originals, new DocumentTextExtractor(), splitter,
                new NoopContextualEnricher(), model, null, clock),
                new DefaultIngestionSinkProcessor(router, model, new NoopSegmentIndexer(), null, auth, registry), clock);
             var query = new KnowledgeQueryService(router, model, new KeywordSearchService(new DocumentMirror(), new SimpleKeywordTokenizer()),
                     5, 0.0, false, 5, null, false, 5, 1.0, 1.0, 1.0)) {
            query.setDocumentRegistry(registry);
            for (int version = 1; version <= 2; version++) {
                String content = version == 1 ? "refund within three days" : "refund within seven days";
                feed.set(new Page(List.of(new Item("faq", version, "Refund", content, false)), "c" + version));
                clock.advance(); service.poll(source);
                String job = service.status(source).pendingJobId(); assertThat(job).isNotNull();
                assertThat(worker.process(source.tenantId(), job)).isTrue();
                clock.advance(); service.poll(source);
                TenantContext.set(new TenantContext.Tenant(source.tenantId(), "reader", Set.of("chat")));
                assertThat(query.query("refund", 5, 0.0, null).hits()).singleElement()
                        .satisfies(hit -> assertThat(hit.text()).isEqualTo(content));
            }
            var original = registry.list(source.tenantId()).getFirst();
            feed.set(new Page(List.of(new Item("faq", 3, null, null, true)), "c3"));
            clock.advance(); service.poll(source);
            TenantContext.set(new TenantContext.Tenant(source.tenantId(), "reader", Set.of("chat")));
            assertThat(query.query("refund", 5, 0.0, null).hits()).isEmpty();
            assertThat(registry.commitVersion(original)).isFalse();
            assertThat(checkpoint.get(source).cursorValue).isEqualTo("c3");
        } finally { server.stop(0); TenantContext.clear(); }
    }
}
