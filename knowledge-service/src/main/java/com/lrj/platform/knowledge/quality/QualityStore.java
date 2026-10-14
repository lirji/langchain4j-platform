package com.lrj.platform.knowledge.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.knowledge.operations.OperationsException;
import com.lrj.platform.knowledge.quality.mapper.*;
import com.lrj.platform.persistence.PlusMapperFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.lrj.platform.protocol.knowledge.RetrievalQuality.*;

/** 评测权威数据复用入库关系库；CAS、活动运行和审计在同一短事务内提交。 */
public final class QualityStore {
    private final QualityMapper db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public QualityStore(DataSource source, ObjectMapper json) {
        this.db = PlusMapperFactory.create(source, QualityMapper.class, 10); this.json = json;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source)); tx.setTimeout(10);
    }
    /** 摘要采用固定UTF-8与SHA256；结构化输入先规范序列化，调用方不传入SQL标识。 */
    public String hash(Object value) { return digest(write(value)); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA256 required", e); }
    }
    /** 租户大小写敏感，避免数据库默认字符排序合并不同租户。 */
    private static String tenant(String id) { return digest(id); }
    private String key(String actor, String idempotency) { return hash(List.of(actor, idempotency)); }

    /** 创建与审计原子提交，同键不同内容不得覆盖已有定义。 */
    public Dataset create(String tenant, Dataset value, Create command) {
        String t = tenant(tenant), key = key(value.updatedBy(), command.idempotencyKey()), hash = hash(command);
        try {
            return tx.execute(status -> {
                one(db.insertDataset(t, value.id(), key, hash, write(value)));
                audit(t, value.id(), value.updatedBy(), "CREATE", value.id(), value.updatedAt()); return value;
            });
        } catch (DuplicateKeyException duplicate) { return request(db.datasetRequest(t, key), hash, Dataset.class); }
    }
    /** 详情只在认证租户中查找，缺失不泄露其他租户资源。 */
    public Dataset get(String tenant, String id) { return body(db.dataset(tenant(tenant), id), Dataset.class); }
    /** 分页上限在存储边界重复校验，避免无界查询。 */
    public List<Dataset> list(String tenant, String before, int limit) {
        page(before, limit); return db.datasets(tenant(tenant), cursor(before), limit).stream().map(row -> body(row, Dataset.class)).toList();
    }
    /** CAS编辑和基线选择同时记录审计，活动运行期间拒绝修改。 */
    public Dataset save(String tenant, Dataset value, long expected, String action) {
        return tx.execute(status -> {
            one(db.updateDataset(tenant(tenant), value.id(), expected, write(value)));
            audit(tenant(tenant), value.id(), value.updatedBy(), action, value.baselineRunId() == null ? value.id() : value.baselineRunId(), value.updatedAt());
            return value;
        });
    }
    /** 重试先读取原意图，不因定义之后被编辑而丢失已完成结果。 */
    public Run requestedRun(String tenant, String dataset, String actor, Execute command) {
        QualityRow row = db.runRequest(tenant(tenant), dataset, key(actor, command.idempotencyKey()));
        return row == null ? null : request(row, hash(command), Run.class);
    }
    /** 返回原意图时id不同，应用服务不得再次调用检索。 */
    public Run start(String tenant, Run run, Execute command) {
        String t = tenant(tenant);
        try {
            return tx.execute(status -> {
                one(db.insertRun(t, run.id(), run.datasetId(), key(run.actor(), command.idempotencyKey()), hash(command), run.deadlineAt(), write(run)));
                one(db.claim(t, run.datasetId(), command.expectedRevision(), run.id()));
                audit(t, run.datasetId(), run.actor(), "START", run.id(), run.startedAt()); return run;
            });
        } catch (DuplicateKeyException duplicate) {
            return request(db.runRequest(t, run.datasetId(), key(run.actor(), command.idempotencyKey())), hash(command), Run.class);
        }
    }
    /** 历史结果只按认证租户读取。 */
    public Run run(String tenant, String id) { return body(db.run(tenant(tenant), id), Run.class); }
    /** 历史分页保留实际运行快照，不拼接可变当前定义。 */
    public List<Run> runs(String tenant, String dataset, String before, int limit) {
        page(before, limit); return db.runs(tenant(tenant), dataset, cursor(before), limit).stream().map(row -> body(row, Run.class)).toList();
    }
    /** 终态和释放租约原子提交；已被终止的旧执行器会冲突而非覆盖结果。 */
    public Run finish(String tenant, Run run, String actor, String action) {
        return tx.execute(status -> {
            one(db.finish(tenant(tenant), run.id(), run.status().name(), write(run)));
            one(db.release(tenant(tenant), run.datasetId(), run.id()));
            audit(tenant(tenant), run.datasetId(), actor, action, run.id(), run.finishedAt()); return run;
        });
    }
    private void audit(String tenant, String dataset, String actor, String action, String subject, long at) {
        one(db.audit(tenant, dataset, actor, action, subject, at));
    }
    private <T> T request(QualityRow row, String hash, Class<T> type) {
        if (row == null || !hash.equals(row.requestHash)) throw OperationsException.conflict();
        return body(row, type);
    }
    private <T> T body(QualityRow row, Class<T> type) {
        if (row == null) throw OperationsException.missing();
        try { return json.readValue(row.bodyJson, type); }
        catch (java.io.IOException e) { throw new IllegalStateException("invalid stored quality record", e); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("quality serialization failed", e); }
    }
    private static void one(int count) { if (count != 1) throw OperationsException.conflict(); }
    private static void page(String before, int limit) {
        if (before == null || before.length() > 64 || limit < 1 || limit > 50) throw new IllegalArgumentException("invalid page");
    }
    private static String cursor(String value) { return "~".equals(value) ? null : value; }
}
