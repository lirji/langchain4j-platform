package com.lrj.platform.knowledge.ingest.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.knowledge.ingest.sync.mapper.*;
import com.lrj.platform.persistence.PlusMapperFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import static com.lrj.platform.knowledge.ingest.sync.SyncData.*;

/** 来源检查点与记录版本在关系库内原子提交，HTTP和入库调用均不占用数据库事务。 */
public final class KnowledgeSyncStore {
    private final SyncMapper db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;
    public KnowledgeSyncStore(DataSource source, ObjectMapper json, Clock clock) {
        this.db = PlusMapperFactory.create(source, SyncMapper.class, 5);
        this.json = json; this.clock = clock;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source)); tx.setTimeout(5);
    }
    /** 领取本轮执行权；来源端点/身份改变需使用新来源ID，不能悄悄复用旧游标。 */
    public SyncRow claim(KnowledgeSyncProperties.Source source) {
        String tenant = hash(source.tenantId()), key = hash(source.id()), config = configHash(source);
        try { db.insert(tenant, key, config); } catch (DuplicateKeyException ignored) { /* 重启或多实例复用。 */ }
        SyncRow current = db.get(tenant, key);
        if (current == null || !config.equals(current.configHash)) throw new SyncFailure("SOURCE_CONFIG_CHANGED");
        String owner = UUID.randomUUID().toString(); long now = clock.millis();
        if (db.claim(tenant, key, config, owner, now, now + 60_000) != 1) return null;
        SyncRow row = db.get(tenant, key);
        if (!owner.equals(row.leaseOwner)) throw new SyncFailure("LEASE_LOST");
        return row;
    }
    /** 状态只在指定租户和来源内读取。 */
    public SyncRow get(KnowledgeSyncProperties.Source source) { return db.get(hash(source.tenantId()), hash(source.id())); }
    /** 映射仅代表已成功的文档，不能把已提交任务当成完成。 */
    public SyncRow document(SyncRow row, String id) { return db.document(row.tenantKey, row.sourceKey, hash(id)); }
    /** 持久化恢复页，确保未知提交结果可用相同版本和幂等键重放。 */
    public void stage(SyncRow row, Pending pending) {
        row.pendingJson = write(pending); row.errorCode = null; row.jobId = null; save(row, false);
    }
    /** 记录版本与页内进度同事务；任何一条失败都回滚，CAS失效的旧执行器不能写映射。 */
    public void completed(SyncRow row, Planned item, Pending next) {
        tx.executeWithoutResult(status -> {
            row.pendingJson = write(next); row.jobId = null; row.errorCode = null;
            save(row, false);
            var old = document(row, item.item().id());
            int count = old == null
                    ? db.insertDocument(row.tenantKey, row.sourceKey, hash(item.item().id()), item.item().revision(), item.hash(), item.documentVersion(), item.documentId(), item.item().id(), item.item().deleted())
                    : db.updateDocument(row.tenantKey, row.sourceKey, hash(item.item().id()), item.item().revision(), item.hash(), item.documentVersion(), item.documentId(), item.item().id(), item.item().deleted());
            if (count != 1) throw new SyncFailure("CHECKPOINT_CONFLICT");
        });
    }
    /** 整页完成才更新来源游标，并清除包含正文的临时恢复快照。 */
    public void commit(SyncRow row, String cursor) {
        row.cursorValue = cursor; row.pendingJson = null; row.errorCode = null; row.jobId = null; save(row, true);
    }
    /** 释放租约并退避；保留页快照和原游标供下轮恢复。 */
    public void release(SyncRow row, String error, String jobId) {
        row.errorCode = error; row.jobId = jobId; save(row, true);
    }
    private void save(SyncRow row, boolean release) {
        long now = clock.millis(); String owner = row.leaseOwner;
        row.leaseUntil = release ? 0 : now + 60_000;
        if (release) { row.leaseOwner = null; row.nextPollAt = now + (row.errorCode == null ? 30_000 : 60_000); }
        if (db.save(row, row.revision, owner, now) != 1) throw new SyncFailure("LEASE_LOST");
        row.revision++;
    }
    /** 快照仅由本服务产生；存储损坏不静默跳过。 */
    public Pending pending(SyncRow row) {
        if (row.pendingJson == null) return null;
        try { return json.readValue(row.pendingJson, Pending.class); }
        catch (Exception failure) { throw new SyncFailure("INVALID_STORED_PAGE"); }
    }
    /** 摘要采用固定序列化边界，避免字符串拼接碰撞。 */
    public String fingerprint(Object value) { return hash(write(value)); }
    /** 凭据轮换允许保留游标，其余身份与端点变化必须显式重新建源。 */
    public String configHash(KnowledgeSyncProperties.Source source) {
        return fingerprint(List.of(source.id(), source.tenantId(), source.userId(),
                source.department() == null ? "" : source.department(), source.endpoint().toASCIIString()));
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new SyncFailure("INVALID_SYNC_STATE"); }
    }
    /** 摘要键保留原始标识大小写语义，避免MySQL默认排序规则合并不同租户。 */
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
