package com.lrj.platform.knowledge.operations;

import com.lrj.platform.knowledge.operations.mapper.KnowledgeOperationsStoreMapper;
import com.lrj.platform.persistence.PlusMapperFactory;
import com.lrj.platform.persistence.Rows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import static com.lrj.platform.knowledge.operations.KnowledgeOperations.*;

/** knowledge域拥有运营持久化；所有SQL带租户摘要，版本变更和审计在同一事务提交。 */
public final class JdbcKnowledgeOperationsStore {
    private final KnowledgeOperationsStoreMapper db;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    public JdbcKnowledgeOperationsStore(DataSource source, ObjectMapper mapper) {
        this.mapper = mapper;
        this.db = PlusMapperFactory.create(source, KnowledgeOperationsStoreMapper.class, 10);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source)); transactions.setTimeout(10);
        db.readContract1();
        db.readContract2();
        db.readContract3();
        db.readContract4();
    }
    /** 幂等键按租户和用户作用域隔离，同键异载荷返回冲突。 */
    public Feedback feedback(String tenant, String actor, FeedbackInput input, long now) {
        String tenantKey = identity(tenant), requestKey = requestKey(actor, input.idempotencyKey());
        String fingerprint = hash(write(input));
        var candidate = new Feedback(newId(now), tenant, actor, input, now);
        try {
            db.writeFeedback1(
                    candidate.id(),
                    tenantKey,
                    identity(actor),
                    requestKey,
                    fingerprint,
                    write(candidate),
                    input.documentId(),
                    input.reason().name());
            return candidate;
        } catch (DuplicateKeyException duplicate) {
            return Rows.map(db.readFeedback1(tenantKey, requestKey), (rs, row) -> {
                        if (!fingerprint.equals(rs.requestHash)) throw OperationsException.conflict();
                        return read(rs.bodyJson, Feedback.class);
                    }).stream().findFirst().orElseThrow(OperationsException::conflict);
        }
    }
    /** 管理入口可省略actor读租户收件箱；调用服务必须先检查ingest权限。 */
    public List<Feedback> feedbacks(String tenant, String actor, String before, int limit) {
        page(before, limit);
        // 首屏不比较哨兵字符：MySQL语言排序中的波浪号不保证大于数字，不能复用H2的字节序假设。
        return Rows.map(db.feedbackPage(identity(tenant), actor == null ? null : identity(actor), before, limit),
                (row, index) -> read(row.bodyJson, Feedback.class));
    }
    /** 文档反馈聚合来自权威反馈表；空结果代表尚无主动反馈，不推断用户满意度。 */
    public java.util.Map<String, Long> feedbackSummary(String tenant, String documentId) {
        java.util.Map<String, Long> result = new java.util.LinkedHashMap<>();
        db.feedbackSummary(identity(tenant), documentId).forEach(row -> result.put(row.reasonCode, row.total));
        return java.util.Map.copyOf(result);
    }

    /** 来源绑定只按同租户查找，避免候选关联到外部反馈。 */
    public Optional<Feedback> feedback(String tenant, String id) {
        return Rows.map(db.readFeedback2(identity(tenant), id), (rs, row) -> read(rs.bodyJson, Feedback.class)).stream().findFirst();
    }
    /** 创建和初始审计原子提交；重复命令返回当前快照，不产生第二个候选。 */
    public Candidate create(Candidate candidate, String key, String fingerprint) {
        String tenant = identity(candidate.tenantId()), request = requestKey(candidate.createdBy(), key);
        try {
            return transactions.execute(status -> {
                db.writeCreate1(candidate.id(), tenant, request, fingerprint, candidate.version(), write(candidate));
                audit(candidate, "CREATE"); return candidate;
            });
        } catch (DuplicateKeyException duplicate) {
            return Rows.map(db.readCreate1(tenant, request), (rs, row) -> {
                        if (!fingerprint.equals(rs.requestHash)) throw OperationsException.conflict();
                        return read(rs.bodyJson, Candidate.class);
                    }).stream().findFirst().orElseThrow(OperationsException::conflict);
        }
    }
    /** 每次读取显式带租户摘要，不依赖连接级隐式上下文。 */
    public Optional<Candidate> get(String tenant, String id) {
        return Rows.map(db.readGet1(identity(tenant), id), (rs, row) -> read(rs.bodyJson, Candidate.class)).stream().findFirst();
    }
    /** 页大小受上限约束，首屏不假设数据库字符排序。 */
    public List<Candidate> list(String tenant, String before, int limit) {
        page(before, limit);
        return Rows.map(db.candidatePage(identity(tenant), before, limit),
                (row, index) -> read(row.bodyJson, Candidate.class));
    }
    /** 影响行数必须为1；审计失败时回滚状态更新，避免无审计的业务成功。 */
    public Candidate save(Candidate candidate, long expected, String action) {
        return transactions.execute(status -> update(candidate, expected, action));
    }
    /** 先占版本再写PUBLISHING，任何一步失败都回滚；远程入库提交在此事务结束后执行。 */
    public Candidate claim(Candidate candidate, long expected) {
        try {
            return transactions.execute(status -> {
                db.writeClaim1(
                        identity(candidate.tenantId()),
                        candidate.documentId(),
                        candidate.release().documentVersion(),
                        candidate.id());
                return update(candidate, expected, "PUBLISH");
            });
        } catch (DuplicateKeyException duplicate) { throw OperationsException.conflict(); }
    }
    /** 返回最近有界记录，长期审计容量由独立生命周期策略管理。 */
    public List<Audit> audit(String tenant, String candidate, int limit) {
        page("~", limit);
        return Rows.map(db.readAudit1(identity(tenant), candidate, limit), (rs, row) -> new Audit(Rows.longValue(rs.id), rs.candidateId, rs.actor, rs.actionCode, Rows.longValue(rs.revision), Rows.longValue(rs.occurredAt)));
    }
    private Candidate update(Candidate candidate, long expected, String action) {
        if (candidate.version() != expected + 1) throw OperationsException.conflict();
        int count = db.writeUpdate1(write(candidate), candidate.version(), identity(candidate.tenantId()), candidate.id(), expected);
        if (count != 1) throw OperationsException.conflict();
        audit(candidate, action); return candidate;
    }
    private void audit(Candidate candidate, String action) {
        db.writeAudit1(
                identity(candidate.tenantId()),
                candidate.id(),
                candidate.updatedBy(),
                action,
                candidate.version(),
                candidate.updatedAt());
    }
    /** 摘要覆盖完整草稿，包括测试用例，防止修改用例后继续使用旧通过证据。 */
    public String fingerprint(Object value) { return hash(write(value)); }
    private static String identity(String value) { required(value, 256, "identity"); return hash(value); }
    private static String requestKey(String actor, String key) {
        required(key, 128, "idempotencyKey"); required(actor, 256, "actor");
        return hash(actor.length() + ":" + actor + key);
    }
    private static void page(String before, int limit) {
        if (before == null || before.length() > 64 || limit < 1 || limit > 100) throw new IllegalArgumentException("invalid cursor/limit");
    }
    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("operation encoding failed", e); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("operation decoding failed", e); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 required", e); }
    }
}
