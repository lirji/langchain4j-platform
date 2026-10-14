package com.lrj.platform.knowledge.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.knowledge.quality.*;
import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.lrj.platform.protocol.knowledge.RetrievalQuality.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 同一套用例在隔离H2和真实MySQL运行，验证租约/终态CAS、幂等及租户边界。 */
class RetrievalQualityTest {
    final TenantContext.Tenant actor = new TenantContext.Tenant("quality-" + UUID.randomUUID(), "editor", Set.of("ingest", "query"));
    final Settings settings = new Settings(5, 0, null, 0, 10000);
    final Definition definition = new Definition("售后检索", List.of(new Case("refund", "多久退款", List.of(new Target("tenant", "refund")), null)), settings);
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    QualityStore store;
    JdbcKnowledgeOperationsStore operations;
    QualityService service;
    AtomicBoolean hits;
    @BeforeEach void setup() {
        var source = OperationsTestDatabase.create(); store = new QualityStore(source, json); operations = new JdbcKnowledgeOperationsStore(source, json);
        hits = new AtomicBoolean(true);
        service = new QualityService(store, operations, (a, token, question, settings) -> reply(a, hits.get()), Clock.systemUTC());
        TenantContext.set(actor);
    }
    @AfterEach void clear() { TenantContext.clear(); }
    private KnowledgeQueryReply reply(TenantContext.Tenant actor, boolean found) {
        return new KnowledgeQueryReply("多久退款", actor.tenantId(), found ? List.of(new KnowledgeHit("chunk", 1.0, "refund", null, null, "0", "正文", "vector", "tenant", "2")) : List.of());
    }
    private Dataset dataset() { return service.create(actor, new Create(UUID.randomUUID().toString(), definition)); }
    private Run execute(Dataset dataset, String key) { return service.execute(actor, dataset.id(), new Execute(dataset.revision(), key, "before"), "transient-token"); }

    @Test void feedbackToBaselineRegressionAndHttpGateFormsARealPersistentLoop() throws Exception {
        var feedback = operations.feedback(actor.tenantId(), "reader", new KnowledgeOperations.FeedbackInput("f", "多久退款", KnowledgeOperations.Reason.NO_ANSWER, null, null), 1);
        var draft = new Definition("feedback", List.of(new Case("f", feedback.input().question(), List.of(new Target("tenant", "refund")), feedback.id())), settings);
        Dataset d = service.create(actor, new Create("create", draft));
        assertThat(service.create(actor, new Create("create", draft))).isEqualTo(d);
        Run first = execute(d, "first");
        assertThat(first.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(first.gate().verdict()).isEqualTo(Verdict.BASELINE_REQUIRED);
        assertThat(json.writeValueAsString(first)).doesNotContain("transient-token", "正文");
        d = service.baseline(actor, d.id(), new Baseline(d.revision(), first.id()));
        hits.set(false); Run second = execute(d, "second");
        assertThat(second.gate().verdict()).isEqualTo(Verdict.REGRESSION);
        assertThat(second.gate().recallDelta()).isEqualTo(-1);
        assertThat(second.gate().reasons()).contains("case_recall:f");
        assertThat(execute(d, "second")).isEqualTo(second);
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("quality", service);
        var mvc = MockMvcBuilders.standaloneSetup(new QualityController(beans.getBeanProvider(QualityService.class), new InternalSecurityProperties())).build();
        mvc.perform(get("/rag/operations/quality/runs/" + second.id() + "/gate")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/rag/operations/quality/runs/" + first.id() + "/gate")).andExpect(status().isConflict());
        hits.set(true); Run third = execute(d, "third");
        mvc.perform(get("/rag/operations/quality/runs/" + third.id() + "/gate")).andExpect(status().isOk());
        assertThat(store.run(actor.tenantId(), second.id()).snapshot()).isEqualTo(draft);
    }

    @Test void scopeTenantSourceContextAndDefinitionAreEnforced() {
        Dataset d = dataset(); Run first = execute(d, "first");
        var other = new TenantContext.Tenant("other", actor.userId(), actor.scopes());
        assertThatThrownBy(() -> service.get(other, d.id())).isInstanceOf(OperationsException.class).hasMessageContaining("not found");
        assertThatThrownBy(() -> service.run(other, first.id())).isInstanceOf(OperationsException.class);
        var reader = new TenantContext.Tenant(actor.tenantId(), "reader", Set.of("query"));
        assertThatThrownBy(() -> service.list(reader, "~", 20)).isInstanceOf(OperationsException.class).hasMessageContaining("ingest");
        var changedActor = new TenantContext.Tenant(actor.tenantId(), "different-editor", actor.scopes());
        assertThatThrownBy(() -> service.baseline(changedActor, d.id(), new Baseline(d.revision(), first.id()))).isInstanceOf(OperationsException.class);
        var foreign = new Definition("foreign", List.of(new Case("x", "q", List.of(new Target("tenant", "doc")), "missing")), settings);
        assertThatThrownBy(() -> service.create(actor, new Create("foreign", foreign))).isInstanceOf(OperationsException.class);
        Dataset selected = service.baseline(actor, d.id(), new Baseline(d.revision(), first.id()));
        Dataset edited = service.edit(actor, d.id(), new Edit(selected.revision(), new Definition("edited", definition.cases(), new Settings(3, 0, null, 0, 0))));
        assertThat(edited.baselineRunId()).isNull();
        assertThatThrownBy(() -> service.baseline(actor, edited.id(), new Baseline(edited.revision(), first.id()))).isInstanceOf(OperationsException.class);
        assertThatThrownBy(() -> execute(d, "stale")).isInstanceOf(OperationsException.class);
        assertThatThrownBy(() -> service.execute(actor, d.id(), new Execute(d.revision(), "first", "different"), "t")).isInstanceOf(OperationsException.class);
    }

    @Test void duplicateChunksAndPublicDocumentsCannotInflateTenantRecall() {
        var sample = definition.cases().getFirst();
        var relevant = new KnowledgeHit("a", 1.0, "refund", null, null, null, null, "vector", "tenant", "2");
        var shared = new KnowledgeHit("b", 1.0, "refund", null, null, null, null, "vector", "public", "1");
        var result = QualityScoring.score(sample, List.of(shared, relevant, relevant), 5, 10);
        assertThat(result.recall()).isEqualTo(1); assertThat(result.precision()).isEqualTo(.5); assertThat(result.mrr()).isEqualTo(.5);
        assertThat(result.retrieved()).hasSize(2);
        assertThatThrownBy(() -> QualityScoring.aggregate(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Definition("empty", List.of(), settings)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void dependencyFailureNeverProducesPassingOrSelectableBaseline() {
        Dataset d = dataset();
        service = new QualityService(store, operations, (a,t,q,s) -> { throw new IllegalStateException("secret upstream"); }, Clock.systemUTC());
        Run run = execute(d, "fail");
        assertThat(run.status()).isEqualTo(Status.FAILED); assertThat(run.metrics()).isNull();
        assertThat(run.failure()).doesNotContain("secret");
        assertThatThrownBy(() -> service.baseline(actor, d.id(), new Baseline(d.revision(), run.id()))).isInstanceOf(OperationsException.class);
        // 租约已释放，失败不把评测集永久锁住。
        assertThat(service.edit(actor, d.id(), new Edit(d.revision(), definition)).revision()).isEqualTo(1);
    }

    @Test void concurrentSameKeyRunsOnlyOnceAndAbandonedWorkerCannotOverwrite() throws Exception {
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1); var calls = new AtomicInteger();
        var millis = new AtomicLong(System.currentTimeMillis());
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        };
        service = new QualityService(store, operations, (a,t,q,s) -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown(); try { if (!resume.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test deadline"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            return reply(a, true);
        }, clock);
        Dataset d = dataset();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> execute(d, "same"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Run active = execute(d, "same"); assertThat(active.status()).isEqualTo(Status.RUNNING); assertThat(calls).hasValue(1);
            assertThatThrownBy(() -> execute(d, "other")).isInstanceOf(OperationsException.class);
            assertThatThrownBy(() -> service.edit(actor, d.id(), new Edit(d.revision(), definition))).isInstanceOf(OperationsException.class);
            assertThatThrownBy(() -> service.abandon(actor, active.id())).isInstanceOf(OperationsException.class);
            millis.addAndGet(120001);
            Run abandoned = service.abandon(actor, active.id()); assertThat(abandoned.status()).isEqualTo(Status.FAILED);
            Run fresh = execute(d, "fresh"); assertThat(fresh.status()).isEqualTo(Status.SUCCEEDED);
            resume.countDown();
            assertThat(future.get(5, TimeUnit.SECONDS).failure()).isEqualTo("expired_run_abandoned");
            assertThat(service.run(actor, active.id())).isEqualTo(abandoned);
            assertThat(service.runs(actor, d.id(), "~", 20)).hasSize(2);
        } finally { resume.countDown(); executor.shutdownNow(); }
    }
}
