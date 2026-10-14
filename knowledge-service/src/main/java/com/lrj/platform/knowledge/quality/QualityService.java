package com.lrj.platform.knowledge.quality;

import com.lrj.platform.knowledge.operations.JdbcKnowledgeOperationsStore;
import com.lrj.platform.knowledge.operations.OperationsException;
import com.lrj.platform.security.TenantContext;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.Semaphore;
import static com.lrj.platform.protocol.knowledge.RetrievalQuality.*;
import static com.lrj.platform.knowledge.operations.KnowledgeOperations.newId;

/** 小批同步检索评测；数据库只负责意图/终态，HTTP调用期间不持有事务或长期保存令牌。 */
public final class QualityService {
    private final QualityStore store;
    private final JdbcKnowledgeOperationsStore feedback;
    private final QualityRetriever retrieval;
    private final Clock clock;
    private final Semaphore slots = new Semaphore(2);
    private static final long RUN_BUDGET_MS = 120000;

    public QualityService(QualityStore store, JdbcKnowledgeOperationsStore feedback, QualityRetriever retrieval, Clock clock) {
        this.store = store; this.feedback = feedback; this.retrieval = retrieval; this.clock = clock;
    }
    /** 创建只接受标注定义，来源反馈必须存在于认证租户，问题不能被悄悄替换。 */
    public Dataset create(TenantContext.Tenant actor, Create command) {
        admin(actor); sources(actor, command.definition());
        return store.create(actor.tenantId(), new Dataset(newId(clock.millis()), 0, command.definition(), null, actor.userId(), clock.millis()), command);
    }
    /** 服务端统一检查管理权限，资源ID不授予访问权。 */
    public Dataset get(TenantContext.Tenant actor, String id) { admin(actor); return store.get(actor.tenantId(), id); }
    /** 列表同样限定租户及管理权限。 */
    public List<Dataset> list(TenantContext.Tenant actor, String before, int limit) { admin(actor); return store.list(actor.tenantId(), before, limit); }
    /** 任何定义编辑清除基线引用；已有运行快照和结果不受影响。 */
    public Dataset edit(TenantContext.Tenant actor, String id, Edit command) {
        Dataset old = get(actor, id); expected(old, command.expectedRevision()); sources(actor, command.definition());
        return store.save(actor.tenantId(), new Dataset(id, old.revision() + 1, command.definition(), null, actor.userId(), clock.millis()), old.revision(), "EDIT");
    }
    /** 先验证所属评测集，再读取同租户历史。 */
    public List<Run> runs(TenantContext.Tenant actor, String id, String before, int limit) {
        get(actor, id); return store.runs(actor.tenantId(), id, before, limit);
    }
    /** 历史问题和标注同样需要管理权限。 */
    public Run run(TenantContext.Tenant actor, String id) { admin(actor); return store.run(actor.tenantId(), id); }
    /** 基线必须是同一评测定义、同一权限上下文的完整成功运行，不接受客户端指标。 */
    public Dataset baseline(TenantContext.Tenant actor, String id, Baseline command) {
        Dataset old = get(actor, id); expected(old, command.expectedRevision());
        Run run = run(actor, command.runId()); compatible(actor, old, run);
        return store.save(actor.tenantId(), new Dataset(id, old.revision() + 1, old.definition(), run.id(), actor.userId(), clock.millis()), old.revision(), "BASELINE");
    }
    /** 同键响应丢失返回原运行；并发有界，另一个活动运行或旧页面提交均明确冲突。 */
    public Run execute(TenantContext.Tenant actor, String id, Execute command, String token) {
        admin(actor);
        Run previous = store.requestedRun(actor.tenantId(), id, actor.userId(), command);
        if (previous != null) return previous;
        Dataset dataset = get(actor, id); expected(dataset, command.expectedRevision());
        Run baseline = dataset.baselineRunId() == null ? null : run(actor, dataset.baselineRunId());
        if (baseline != null) compatible(actor, dataset, baseline);
        if (!slots.tryAcquire()) throw new OperationsException("unavailable", "evaluation capacity busy; retry later");
        try {
            long now = clock.millis();
            Run intended = new Run(newId(now), id, store.hash(dataset.definition()), access(actor), actor.userId(),
                    dataset.definition(), dataset.revision(), command.label(), dataset.baselineRunId(), Status.RUNNING,
                    now, now + RUN_BUDGET_MS, null, List.of(), null, null, null);
            Run active = store.start(actor.tenantId(), intended, command);
            if (!active.id().equals(intended.id())) return active;
            var results = new ArrayList<CaseResult>();
            Run terminal;
            try {
                for (Case sample : active.snapshot().cases()) {
                    if (clock.millis() >= active.deadlineAt()) throw new IllegalStateException("deadline");
                    long start = System.nanoTime();
                    var reply = retrieval.retrieve(actor, token, sample.question(), active.snapshot().settings());
                    if (reply == null || !actor.tenantId().equals(reply.tenantId())) throw new IllegalStateException("identity mismatch");
                    results.add(QualityScoring.score(sample, reply.hits(), active.snapshot().settings().topK(), (System.nanoTime() - start) / 1_000_000));
                }
                if (clock.millis() >= active.deadlineAt()) throw new IllegalStateException("deadline");
                Metrics metrics = QualityScoring.aggregate(results);
                terminal = finish(active, Status.SUCCEEDED, results, metrics, QualityScoring.compare(metrics, results, baseline, active.snapshot().settings()), null);
            } catch (RuntimeException failure) {
                // 依赖错误或部分结果不能成为质量通过证据；异常正文可能含地址/凭据，因此只保存稳定原因。
                terminal = finish(active, Status.FAILED, results, null, failedGate(), "retrieval_failed_or_deadline");
            }
            try { return store.finish(actor.tenantId(), terminal, actor.userId(), "FINISH"); }
            catch (OperationsException conflict) {
                Run saved = store.run(actor.tenantId(), active.id());
                if (saved.status() != Status.RUNNING) return saved;
                throw conflict;
            }
        } finally { slots.release(); }
    }
    /** 崩溃或超时后显式终止过期意图；旧执行器之后的提交会被终态CAS拒绝。 */
    public Run abandon(TenantContext.Tenant actor, String id) {
        Run old = run(actor, id);
        if (old.status() != Status.RUNNING) return old;
        if (clock.millis() < old.deadlineAt()) throw OperationsException.conflict();
        return store.finish(actor.tenantId(), finish(old, Status.FAILED, old.results(), null, failedGate(), "expired_run_abandoned"), actor.userId(), "ABANDON");
    }
    private Run finish(Run old, Status status, List<CaseResult> results, Metrics metrics, Gate gate, String failure) {
        return new Run(old.id(), old.datasetId(), old.datasetHash(), old.accessHash(), old.actor(), old.snapshot(), old.datasetRevision(),
                old.label(), old.baselineRunId(), status, old.startedAt(), old.deadlineAt(), clock.millis(), results, metrics, gate, failure);
    }
    private static Gate failedGate() { return new Gate(Verdict.FAILED, List.of("incomplete_evaluation"), null, null, null, null, null); }
    private void compatible(TenantContext.Tenant actor, Dataset dataset, Run run) {
        if (run.status() != Status.SUCCEEDED || !dataset.id().equals(run.datasetId())
                || !store.hash(dataset.definition()).equals(run.datasetHash()) || !access(actor).equals(run.accessHash()))
            throw new OperationsException("conflict", "baseline requires the same dataset definition and authenticated permission context");
    }
    private String access(TenantContext.Tenant actor) {
        return store.hash(Arrays.asList(actor.tenantId(), actor.userId(), actor.department(), actor.scopes().stream().sorted().toList()));
    }
    private void sources(TenantContext.Tenant actor, Definition definition) {
        for (Case sample : definition.cases()) if (sample.feedbackId() != null) {
            var source = feedback.feedback(actor.tenantId(), sample.feedbackId()).orElseThrow(OperationsException::missing);
            if (!sample.question().equals(source.input().question())) throw new IllegalArgumentException("feedback question must match source");
        }
    }
    private static void admin(TenantContext.Tenant actor) {
        if (!actor.hasScope("ingest")) throw new OperationsException("forbidden", "ingest scope required");
    }
    private static void expected(Dataset value, long expected) { if (value.revision() != expected) throw OperationsException.conflict(); }
}
