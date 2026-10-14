package com.lrj.platform.knowledge.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    public JdbcKnowledgeOperationsStore(DataSource source, ObjectMapper mapper) {
        this.mapper = mapper;
        jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(10);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source)); transactions.setTimeout(10);
        jdbc.queryForList("SELECT ID FROM KNOWLEDGE_OPERATION_CANDIDATE WHERE 1=0");
        jdbc.queryForList("SELECT ID FROM KNOWLEDGE_OPERATION_FEEDBACK WHERE 1=0");
        jdbc.queryForList("SELECT ID FROM KNOWLEDGE_OPERATION_AUDIT WHERE 1=0");
        jdbc.queryForList("SELECT CANDIDATE_ID FROM KNOWLEDGE_OPERATION_RELEASE WHERE 1=0");
    }
    /** 幂等键按租户和用户作用域隔离，同键异载荷返回冲突。 */
    public Feedback feedback(String tenant, String actor, FeedbackInput input, long now) {
        String tenantKey = identity(tenant), requestKey = requestKey(actor, input.idempotencyKey());
        String fingerprint = hash(write(input));
        var candidate = new Feedback(newId(now), tenant, actor, input, now);
        try {
            jdbc.update("INSERT INTO KNOWLEDGE_OPERATION_FEEDBACK (ID,TENANT_KEY,AUTHOR_KEY,REQUEST_KEY,REQUEST_HASH,BODY_JSON,DOCUMENT_ID,REASON_CODE) VALUES (?,?,?,?,?,?,?,?)",
                    candidate.id(), tenantKey, identity(actor), requestKey, fingerprint, write(candidate), input.documentId(), input.reason().name());
            return candidate;
        } catch (DuplicateKeyException duplicate) {
            return jdbc.query("SELECT REQUEST_HASH,BODY_JSON FROM KNOWLEDGE_OPERATION_FEEDBACK WHERE TENANT_KEY=? AND REQUEST_KEY=?",
                    (rs, row) -> {
                        if (!fingerprint.equals(rs.getString(1))) throw OperationsException.conflict();
                        return read(rs.getString(2), Feedback.class);
                    }, tenantKey, requestKey).stream().findFirst().orElseThrow(OperationsException::conflict);
        }
    }
    /** 管理入口可省略actor读租户收件箱；调用服务必须先检查ingest权限。 */
    public List<Feedback> feedbacks(String tenant, String actor, String before, int limit) {
        page(before, limit);
        // 首屏不比较哨兵字符：MySQL语言排序中的波浪号不保证大于数字，不能复用H2的字节序假设。
        String sql = "SELECT BODY_JSON FROM KNOWLEDGE_OPERATION_FEEDBACK WHERE TENANT_KEY=?"
                + ("~".equals(before) ? "" : " AND ID<?")
                + (actor == null ? "" : " AND AUTHOR_KEY=?") + " ORDER BY ID DESC LIMIT ?";
        java.util.List<Object> parameters = new java.util.ArrayList<>(); parameters.add(identity(tenant));
        if (!"~".equals(before)) parameters.add(before);
        if (actor != null) parameters.add(identity(actor));
        parameters.add(limit);
        Object[] args = parameters.toArray();
        return jdbc.query(sql, (rs, row) -> read(rs.getString(1), Feedback.class), args);
    }
    /** 文档反馈聚合来自权威反馈表；空结果代表尚无主动反馈，不推断用户满意度。 */
    public java.util.Map<String, Long> feedbackSummary(String tenant, String documentId) {
        java.util.Map<String, Long> result = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT REASON_CODE,COUNT(*) AS TOTAL FROM KNOWLEDGE_OPERATION_FEEDBACK WHERE TENANT_KEY=? AND DOCUMENT_ID=? GROUP BY REASON_CODE",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getString(1), rs.getLong(2)), identity(tenant), documentId);
        return java.util.Map.copyOf(result);
    }

    /** 来源绑定只按同租户查找，避免候选关联到外部反馈。 */
    public Optional<Feedback> feedback(String tenant, String id) {
        return jdbc.query("SELECT BODY_JSON FROM KNOWLEDGE_OPERATION_FEEDBACK WHERE TENANT_KEY=? AND ID=?",
                (rs, row) -> read(rs.getString(1), Feedback.class), identity(tenant), id).stream().findFirst();
    }
    /** 创建和初始审计原子提交；重复命令返回当前快照，不产生第二个候选。 */
    public Candidate create(Candidate candidate, String key, String fingerprint) {
        String tenant = identity(candidate.tenantId()), request = requestKey(candidate.createdBy(), key);
        try {
            return transactions.execute(status -> {
                jdbc.update("INSERT INTO KNOWLEDGE_OPERATION_CANDIDATE (ID,TENANT_KEY,REQUEST_KEY,REQUEST_HASH,REVISION,BODY_JSON) VALUES (?,?,?,?,?,?)",
                        candidate.id(), tenant, request, fingerprint, candidate.version(), write(candidate));
                audit(candidate, "CREATE"); return candidate;
            });
        } catch (DuplicateKeyException duplicate) {
            return jdbc.query("SELECT REQUEST_HASH,BODY_JSON FROM KNOWLEDGE_OPERATION_CANDIDATE WHERE TENANT_KEY=? AND REQUEST_KEY=?",
                    (rs, row) -> {
                        if (!fingerprint.equals(rs.getString(1))) throw OperationsException.conflict();
                        return read(rs.getString(2), Candidate.class);
                    }, tenant, request).stream().findFirst().orElseThrow(OperationsException::conflict);
        }
    }
    /** 每次读取显式带租户摘要，不依赖连接级隐式上下文。 */
    public Optional<Candidate> get(String tenant, String id) {
        return jdbc.query("SELECT BODY_JSON FROM KNOWLEDGE_OPERATION_CANDIDATE WHERE TENANT_KEY=? AND ID=?",
                (rs, row) -> read(rs.getString(1), Candidate.class), identity(tenant), id).stream().findFirst();
    }
    /** 页大小受上限约束，首屏不假设数据库字符排序。 */
    public List<Candidate> list(String tenant, String before, int limit) {
        page(before, limit);
        String sql = "SELECT BODY_JSON FROM KNOWLEDGE_OPERATION_CANDIDATE WHERE TENANT_KEY=?"
                + ("~".equals(before) ? "" : " AND ID<?") + " ORDER BY ID DESC LIMIT ?";
        Object[] args = "~".equals(before) ? new Object[]{identity(tenant), limit} : new Object[]{identity(tenant), before, limit};
        return jdbc.query(sql, (rs, row) -> read(rs.getString(1), Candidate.class), args);
    }
    /** 影响行数必须为1；审计失败时回滚状态更新，避免无审计的业务成功。 */
    public Candidate save(Candidate candidate, long expected, String action) {
        return transactions.execute(status -> update(candidate, expected, action));
    }
    /** 先占版本再写PUBLISHING，任何一步失败都回滚；远程入库提交在此事务结束后执行。 */
    public Candidate claim(Candidate candidate, long expected) {
        try {
            return transactions.execute(status -> {
                jdbc.update("INSERT INTO KNOWLEDGE_OPERATION_RELEASE (TENANT_KEY,DOCUMENT_ID,DOCUMENT_VERSION,CANDIDATE_ID) VALUES (?,?,?,?)",
                        identity(candidate.tenantId()), candidate.documentId(), candidate.release().documentVersion(), candidate.id());
                return update(candidate, expected, "PUBLISH");
            });
        } catch (DuplicateKeyException duplicate) { throw OperationsException.conflict(); }
    }
    /** 返回最近有界记录，长期审计容量由独立生命周期策略管理。 */
    public List<Audit> audit(String tenant, String candidate, int limit) {
        page("~", limit);
        return jdbc.query("SELECT ID,CANDIDATE_ID,ACTOR,ACTION_CODE,REVISION,OCCURRED_AT FROM KNOWLEDGE_OPERATION_AUDIT "
                        + "WHERE TENANT_KEY=? AND CANDIDATE_ID=? ORDER BY ID DESC LIMIT ?",
                (rs, row) -> new Audit(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getLong(6)),
                identity(tenant), candidate, limit);
    }
    private Candidate update(Candidate candidate, long expected, String action) {
        if (candidate.version() != expected + 1) throw OperationsException.conflict();
        int count = jdbc.update("UPDATE KNOWLEDGE_OPERATION_CANDIDATE SET BODY_JSON=?,REVISION=? WHERE TENANT_KEY=? AND ID=? AND REVISION=?",
                write(candidate), candidate.version(), identity(candidate.tenantId()), candidate.id(), expected);
        if (count != 1) throw OperationsException.conflict();
        audit(candidate, action); return candidate;
    }
    private void audit(Candidate candidate, String action) {
        jdbc.update("INSERT INTO KNOWLEDGE_OPERATION_AUDIT (TENANT_KEY,CANDIDATE_ID,ACTOR,ACTION_CODE,REVISION,OCCURRED_AT) VALUES (?,?,?,?,?,?)",
                identity(candidate.tenantId()), candidate.id(), candidate.updatedBy(), action, candidate.version(), candidate.updatedAt());
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
