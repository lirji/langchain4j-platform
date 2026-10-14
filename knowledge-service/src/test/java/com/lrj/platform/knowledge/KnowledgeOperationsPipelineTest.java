package com.lrj.platform.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.knowledge.authz.NoopKnowledgeAuthz;
import com.lrj.platform.knowledge.es.NoopSegmentIndexer;
import com.lrj.platform.knowledge.hybrid.KeywordSearchService;
import com.lrj.platform.knowledge.hybrid.SimpleKeywordTokenizer;
import com.lrj.platform.knowledge.ingest.NoopContextualEnricher;
import com.lrj.platform.knowledge.ingest.job.*;
import com.lrj.platform.knowledge.lifecycle.*;
import com.lrj.platform.knowledge.operations.*;
import com.lrj.platform.knowledge.store.InMemoryEmbeddingStoreRouter;
import com.lrj.platform.migrations.*;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import static com.lrj.platform.knowledge.operations.KnowledgeOperations.*;
import static org.assertj.core.api.Assertions.*;

/** 真实应用链路从反馈到worker再到检索；离线HashEmbedding不冒充外部模型/向量库集成。 */
class KnowledgeOperationsPipelineTest {
    @AfterEach void clear() { TenantContext.clear(); }
    @Test void feedbackToPublicationRetrievalAndNewVersionRollback() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:ops-pipeline;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        SchemaMigrationRunner.migrate(ds, SchemaName.KNOWLEDGE_INGESTION);
        var mapper = new ObjectMapper(); var clock = Clock.systemUTC();
        var store = new JdbcKnowledgeOperationsStore(ds, mapper);
        var operations = new KnowledgeOperationsService(store, clock);
        var jobs = new JdbcIngestionJobStore(ds, mapper);
        var registry = new InMemoryDocumentRegistry(); var router = new InMemoryEmbeddingStoreRouter();
        var sources = new InMemoryDocumentSourceStore(); var authorization = new NoopKnowledgeAuthz();
        var submissions = new IngestionSubmissionService(sources, jobs, registry, authorization, clock,
                Set.of(IngestionSink.VECTOR, IngestionSink.REGISTRY));
        var publication = new KnowledgePublicationService(operations, store, submissions, jobs, registry, clock);
        var model = new KnowledgeEmbeddingConfig.HashEmbeddingModel();
        var splitter = new DocumentSplitterFactory("recursive", "chars", 300, 0, 0, "gpt-4o-mini", "recursive", 300, 0, 1, 95, 200, 0, model);
        var worker = new IngestionJobWorker(jobs, new DefaultIngestionDocumentPreparer(sources, new DocumentTextExtractor(), splitter,
                new NoopContextualEnricher(), model, null, clock),
                new DefaultIngestionSinkProcessor(router, model, new NoopSegmentIndexer(), null, authorization, registry), clock);
        var query = new KnowledgeQueryService(router, model, new KeywordSearchService(new DocumentMirror(), new SimpleKeywordTokenizer()),
                5, 0.0, false, 5, null, false, 5, 1.0, 1.0, 1.0);
        query.setDocumentRegistry(registry);
        var probe = new KnowledgeRetrievalProbe(query, null, "X-Internal-Token");
        var actor = new TenantContext.Tenant("pipeline", "editor", Set.of("ingest", "chat"), "dept");
        TenantContext.set(actor);
        var feedback = operations.feedback(actor, new FeedbackInput("feedback", "refund phoenix", Reason.NO_ANSWER, "missing", null));
        var original = operations.create(actor, new CandidateInput("candidate", feedback.id(),
                new Draft("policy.txt", "refund phoenix within three days", "policy", List.of(new Check("refund phoenix", "three days")))));
        var current = original;
        for (int version = 1; version <= 2; version++) {
            var evaluated = operations.evaluate(actor, current.id(), current.version());
            var approved = publication.approve(actor, current.id(), evaluated.version());
            var publishing = publication.publish(actor, current.id(), approved.version());
            assertThat(worker.process(actor.tenantId(), publishing.release().jobId())).isTrue();
            TenantContext.set(actor);
            var published = publication.sync(actor, current.id(), publishing.version());
            assertThat(published.status()).isEqualTo(Status.PUBLISHED);
            assertThat(published.release().documentVersion()).isEqualTo(version);
            var checked = publication.probe(actor, current.id(), published.version(), null, probe);
            assertThat(checked.probe().successful()).isTrue();
            if (version == 1) current = publication.rollbackDraft(actor, current.id(), "rollback");
        }
        operations.feedback(actor, new FeedbackInput("helpful", "refund phoenix", Reason.HELPFUL, "fixed", original.documentId()));
        assertThat(operations.feedbackSummary(actor, current.id())).containsEntry("HELPFUL", 1L);
        var first = operations.get(actor, original.id());
        assertThatThrownBy(() -> publication.probe(actor, original.id(), first.version(), null, probe)).isInstanceOf(OperationsException.class);
        // 使用真正的query、HashEmbedding和版本过滤器形成检索闭环，不用固定HTTP结果代替检索。
        var quality = new com.lrj.platform.knowledge.quality.QualityService(
                new com.lrj.platform.knowledge.quality.QualityStore(ds, mapper), store, probe, clock);
        var definition = new com.lrj.platform.protocol.knowledge.RetrievalQuality.Definition("policy quality",
                List.of(new com.lrj.platform.protocol.knowledge.RetrievalQuality.Case("policy", "refund phoenix",
                        List.of(new com.lrj.platform.protocol.knowledge.RetrievalQuality.Target("tenant", original.documentId())), feedback.id())),
                new com.lrj.platform.protocol.knowledge.RetrievalQuality.Settings(5, 0, "policy", 0, 10000));
        var dataset = quality.create(actor, new com.lrj.platform.protocol.knowledge.RetrievalQuality.Create("quality", definition));
        var baseline = quality.execute(actor, dataset.id(), new com.lrj.platform.protocol.knowledge.RetrievalQuality.Execute(0L, "baseline", "v2"), null);
        assertThat(baseline.metrics().recall()).isEqualTo(1);
        assertThat(baseline.results().getFirst().retrieved().getFirst().version()).isEqualTo("2");
        dataset = quality.baseline(actor, dataset.id(), new com.lrj.platform.protocol.knowledge.RetrievalQuality.Baseline(0L, baseline.id()));
        registry.remove(actor.tenantId(), original.documentId());
        var regression = quality.execute(actor, dataset.id(), new com.lrj.platform.protocol.knowledge.RetrievalQuality.Execute(dataset.revision(), "regression", "document removed"), null);
        assertThat(regression.gate().verdict()).isEqualTo(com.lrj.platform.protocol.knowledge.RetrievalQuality.Verdict.REGRESSION);
        TenantContext.set(new TenantContext.Tenant("foreign", "editor", Set.of("chat")));
        assertThat(query.query("refund phoenix", 5, 0.0, "policy").hits()).isEmpty();
    }
}
