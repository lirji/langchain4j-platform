package com.lrj.platform.knowledge.operations;

import com.lrj.platform.security.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import static com.lrj.platform.knowledge.operations.KnowledgeOperations.*;

/** 运营应用服务：所有用户决策都经过状态/权限/版本校验，不自动发布模型生成内容。 */
public class KnowledgeOperationsService {
    private final JdbcKnowledgeOperationsStore store;
    private final Clock clock;
    public KnowledgeOperationsService(JdbcKnowledgeOperationsStore store, Clock clock) {
        this.store = store; this.clock = clock;
    }
    /** 主动反馈仅绑定认证用户，客户端不能伪造租户与提交者。 */
    public Feedback feedback(TenantContext.Tenant actor, FeedbackInput input) {
        return store.feedback(actor.tenantId(), actor.userId(), input, clock.millis());
    }
    /** 管理与本人视图共享查询逻辑，但只有管理视图可省略作者过滤。 */
    public List<Feedback> feedbacks(TenantContext.Tenant actor, boolean inbox, String before, int limit) {
        if (inbox) requireAdmin(actor);
        return store.feedbacks(actor.tenantId(), inbox ? null : actor.userId(), before, limit);
    }
    /** 来源反馈必须属于同租户；未信任模型/客户端提供任何已通过标志。 */
    public Candidate create(TenantContext.Tenant actor, CandidateInput input) {
        requireAdmin(actor);
        if (input.feedbackId() != null) store.feedback(actor.tenantId(), input.feedbackId()).orElseThrow(OperationsException::missing);
        var candidate = Candidate.create(actor.tenantId(), actor.userId(), input.feedbackId(), null,
                "ops-" + UUID.randomUUID(), input.draft(), clock.millis());
        return store.create(candidate, input.idempotencyKey(), store.fingerprint(input));
    }
    /** 同时核对管理权限和租户，资源ID本身不能授予访问权。 */
    public Candidate get(TenantContext.Tenant actor, String id) {
        requireAdmin(actor); return store.get(actor.tenantId(), id).orElseThrow(OperationsException::missing);
    }
    /** 管理列表同样受租户边界保护，不能只在详情校验。 */
    public List<Candidate> list(TenantContext.Tenant actor, String before, int limit) {
        requireAdmin(actor); return store.list(actor.tenantId(), before, limit);
    }
    /** 只聚合当前候选关联文档的真实主动反馈，不把反馈数量当作完整线上质量指标。 */
    public java.util.Map<String, Long> feedbackSummary(TenantContext.Tenant actor, String id) {
        Candidate candidate = get(actor, id);
        return store.feedbackSummary(actor.tenantId(), candidate.documentId());
    }
    /** 先授权候选再读审计，防止日志侧泄露其他租户操作。 */
    public List<Audit> history(TenantContext.Tenant actor, String id, int limit) {
        get(actor, id); return store.audit(actor.tenantId(), id, limit);
    }
    /** 编辑使旧评测、审核及探针失效；已经发起发布的内容不能原地修改。 */
    public Candidate edit(TenantContext.Tenant actor, String id, long version, Draft draft) {
        var current = version(actor, id, version);
        editable(current);
        if (draft == null) throw new IllegalArgumentException("draft required");
        return store.save(current.change(draft, Status.DRAFT, null, null, null, actor.userId(), clock.millis()), version, "EDIT");
    }
    /** 真实执行确定性内容契约检查，不把它冒充语义正确性或线上检索质量。 */
    public Candidate evaluate(TenantContext.Tenant actor, String id, long version) {
        var current = version(actor, id, version); editable(current);
        List<CheckResult> results = current.draft().checks().stream().map(check -> {
            boolean passed = current.draft().content().contains(check.requiredText());
            return new CheckResult(check.question(), passed, passed ? "required_text_present" : "required_text_missing");
        }).toList();
        var evidence = new Evidence("CONTENT_CONTRACT", store.fingerprint(current.draft()), results.size(),
                (int) results.stream().filter(CheckResult::passed).count(), clock.millis(), results);
        return store.save(current.change(current.draft(), evidence.successful() ? Status.EVALUATED : Status.DRAFT,
                evidence, null, null, actor.userId(), clock.millis()), version, "EVALUATE");
    }
    private Candidate version(TenantContext.Tenant actor, String id, long expected) {
        var current = get(actor, id);
        if (expected < 0 || current.version() != expected) throw OperationsException.conflict();
        return current;
    }
    private static void requireAdmin(TenantContext.Tenant actor) {
        if (!actor.hasScope("ingest")) throw new OperationsException("forbidden", "ingest scope required");
    }
    private static void editable(Candidate candidate) {
        if (candidate.status() == Status.PUBLISHING || candidate.status() == Status.PUBLISH_FAILED || candidate.status() == Status.PUBLISHED)
            throw OperationsException.conflict();
    }
}
