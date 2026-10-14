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
import java.util.ArrayList;
import static com.lrj.platform.protocol.knowledge.KnowledgeSyncOperations.*;
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
        return claim(source, "scheduler");
    }
    /** 首次触发身份写入运行后不可被恢复调度覆盖。 */
    public SyncRow claim(KnowledgeSyncProperties.Source source, String triggeredBy) {
        return claim(source, triggeredBy, null);
    }
    /** 重试必须绑定请求时看到的原任务；领取前再次校验，防止恢复另一页任务。 */
    public SyncRow claim(KnowledgeSyncProperties.Source source, String triggeredBy, String expectedJobId) {
        ensure(source);
        return tx.execute(status -> {
            String owner = UUID.randomUUID().toString(); long now = clock.millis();
            if (expectedJobId != null) {
                var current = db.lock(hash(source.tenantId()), hash(source.id()));
                if (!expectedJobId.equals(current.jobId) || current.activeRunId == null) throw new SyncFailure("NO_RETRYABLE_JOB");
                if (current.paused) throw new SyncFailure("SOURCE_PAUSED");
            }
            if (db.claim(hash(source.tenantId()), hash(source.id()), configHash(source), owner, now, now + 60_000) != 1) return null;
            SyncRow row = get(source);
            if (!owner.equals(row.leaseOwner)) throw new SyncFailure("LEASE_LOST");
            if (row.activeRunId == null) {
                row.activeRunId = UUID.randomUUID().toString();
                Pending restored = pending(row);
                List<RecordResult> confirmed = restored == null ? List.of() : restored.items().stream().limit(restored.index())
                        .map(item -> result(item, RecordState.SUCCEEDED, null, null)).toList();
                var run = new Run(row.activeRunId, null, source.id(), RunState.RUNNING.code(), triggeredBy, source.userId(), now, now,
                        null, row.configHash, hash(row.cursorValue), null, restored == null ? null : Integer.valueOf(restored.items().size()),
                        restored == null ? 0 : restored.items().size() - restored.index(), row.errorCode, confirmed);
                db.insertRun(row, write(run)); save(row, false);
            } else updateRun(row, RunState.RUNNING, null, null);
            return row;
        });
    }
    private void ensure(KnowledgeSyncProperties.Source source) {
        try { db.insert(hash(source.tenantId()), hash(source.id()), configHash(source)); }
        catch (DuplicateKeyException ignored) { /* 重启或多实例复用。 */ }
        var current = get(source);
        if (current == null || !configHash(source).equals(current.configHash)) throw new SyncFailure("SOURCE_CONFIG_CHANGED");
    }
    /** 独立控制版本防止页面覆盖他人操作；暂停也撤销在途协调器租约。 */
    public void control(KnowledgeSyncProperties.Source source, long expected, boolean paused) {
        ensure(source);
        tx.executeWithoutResult(status -> {
            var row = db.lock(hash(source.tenantId()), hash(source.id()));
            if (expected < 0 || db.control(row.tenantKey, row.sourceKey, expected, paused) != 1)
                throw new SyncFailure("CONTROL_CONFLICT");
            if (row.activeRunId != null) updateRun(row, paused ? RunState.PAUSED : row.errorCode == null ? RunState.WAITING : RunState.BLOCKED, null, null);
        });
    }
    /** 提前安排轮询；暂停时返回冲突，不能通过手动触发绕过控制。 */
    public void wake(KnowledgeSyncProperties.Source source) {
        ensure(source);
        if (db.wake(hash(source.tenantId()), hash(source.id())) != 1) throw new SyncFailure("SOURCE_PAUSED");
    }
    /** 状态只在指定租户和来源内读取。 */
    public SyncRow get(KnowledgeSyncProperties.Source source) { return db.get(hash(source.tenantId()), hash(source.id())); }
    /** 映射仅代表已成功的文档，不能把已提交任务当成完成。 */
    public SyncRow document(SyncRow row, String id) { return db.document(row.tenantKey, row.sourceKey, hash(id)); }
    /** 持久化恢复页，确保未知提交结果可用相同版本和幂等键重放。 */
    public void stage(SyncRow row, Pending pending) {
        tx.executeWithoutResult(status -> {
            row.pendingJson = write(pending); row.errorCode = null; row.jobId = null; save(row, false);
            updateRun(row, RunState.RUNNING, null, null);
        });
    }
    /** 记录版本与页内进度同事务；任何一条失败都回滚，CAS失效的旧执行器不能写映射。 */
    public void completed(SyncRow row, Planned item, Pending next) {
        tx.executeWithoutResult(status -> {
            row.pendingJson = write(next); row.jobId = null; row.errorCode = null;
            save(row, false);
            var old = document(row, item.item().id());
            // 已消费过的版本作为跳过结果保存，不能倒退或重复写入映射。
            if (Action.SKIPPED.matches(item.action()) && old != null && item.item().revision() <= old.sourceRevision) {
                updateRun(row, RunState.RUNNING, result(item, RecordState.SUCCEEDED, null, null), null); return;
            }
            int count = old == null
                    ? db.insertDocument(row.tenantKey, row.sourceKey, hash(item.item().id()), item.item().revision(), item.hash(), item.documentVersion(), item.documentId(), item.item().id(), item.item().deleted())
                    : db.updateDocument(row.tenantKey, row.sourceKey, hash(item.item().id()), item.item().revision(), item.hash(), item.documentVersion(), item.documentId(), item.item().id(), item.item().deleted());
            if (count != 1) throw new SyncFailure("CHECKPOINT_CONFLICT");
            updateRun(row, RunState.RUNNING, result(item, RecordState.SUCCEEDED, null, null), null);
        });
    }
    /** 整页完成才更新来源游标，并清除包含正文的临时恢复快照。 */
    public void commit(SyncRow row, String cursor) {
        tx.executeWithoutResult(status -> {
            row.cursorValue = cursor; row.errorCode = null; row.jobId = null; row.lastSuccessAt = clock.millis();
            String runId = row.activeRunId;
            row.activeRunId = null; save(row, true); row.activeRunId = runId;
            updateRun(row, RunState.SUCCEEDED, null, hash(cursor));
            row.activeRunId = null; row.pendingJson = null;
            // 同一事务下清空快照；save已释放租约，专用清理条件绑定刚写入的版本。
            if (db.clearPage(row) != 1) throw new SyncFailure("CHECKPOINT_CONFLICT");
        });
    }
    /** 释放租约并退避；保留页快照和原游标供下轮恢复。 */
    public void release(SyncRow row, String error, String jobId) {
        tx.executeWithoutResult(status -> {
            row.errorCode = error; row.jobId = jobId; save(row, true);
            Pending page = pending(row);
            RecordResult result = page != null && page.index() < page.items().size()
                    ? result(page.items().get(page.index()), error == null ? RecordState.WAITING : RecordState.FAILED, error, jobId) : null;
            updateRun(row, error == null ? RunState.WAITING : RunState.BLOCKED, result, null);
        });
    }
    /** 分页仅返回来源内运行，先取limit+1判断是否还有下一页。 */
    public Runs runs(KnowledgeSyncProperties.Source source, String before, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be 1..50");
        long cursor;
        try { cursor = before == null ? Long.MAX_VALUE : Long.parseLong(before); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("invalid run cursor"); }
        if (cursor <= 0) throw new IllegalArgumentException("invalid run cursor");
        var rows = db.runs(hash(source.tenantId()), hash(source.id()), cursor, limit + 1);
        var items = rows.stream().limit(limit).map(this::readRun).toList();
        return new Runs(items, rows.size() > limit ? items.getLast().sequence() : null);
    }
    /** 不存在与跨租户运行均返回空，调用方统一返回404。 */
    public Run run(KnowledgeSyncProperties.Source source, String id) {
        var row = db.run(hash(source.tenantId()), hash(source.id()), id);
        return row == null ? null : readRun(row);
    }
    private Run readRun(SyncRunRow row) {
        try {
            // 统计字段由条目结果推导；兼容历史JSON中的派生统计字段。
            Run r = json.readerFor(Run.class).without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(row.bodyJson);
            return new Run(r.id(), Long.toString(row.seq), r.sourceId(), r.status(), r.triggeredBy(), r.executionUserId(),
                    r.startedAt(), r.updatedAt(), r.finishedAt(), r.configHash(), r.checkpointBeforeHash(), r.checkpointAfterHash(),
                    r.fetchedCount(), r.remaining(), r.errorCode(), r.records());
        } catch (Exception invalid) { throw new SyncFailure("INVALID_RUN_STATE"); }
    }
    private RecordResult result(Planned p, RecordState status, String error, String job) {
        String action = p.action() == null ? p.item().deleted() ? Action.DELETED.code() : Action.LEGACY_CONFIRMED.code() : p.action();
        return new RecordResult(p.item().id(), p.item().revision(), p.documentId(), action, status.code(), error, job);
    }
    private void updateRun(SyncRow row, RunState state, RecordResult result, String after) {
        if (row.activeRunId == null) return;
        Run previous = readRun(db.run(row.tenantKey, row.sourceKey, row.activeRunId));
        if (RunState.SUCCEEDED.matches(previous.status())) throw new SyncFailure("RUN_FINISHED");
        var records = new ArrayList<>(previous.records());
        if (result != null) { records.removeIf(r -> r.externalId().equals(result.externalId())); records.add(result); }
        Pending page = pending(row);
        Run next = new Run(previous.id(), previous.sequence(), previous.sourceId(), state.code(), previous.triggeredBy(), previous.executionUserId(),
                previous.startedAt(), clock.millis(), state == RunState.SUCCEEDED ? clock.millis() : null, previous.configHash(),
                previous.checkpointBeforeHash(), after == null ? previous.checkpointAfterHash() : after,
                page == null ? previous.fetchedCount() : Integer.valueOf(page.items().size()), page == null ? previous.remaining() : page.items().size() - page.index(),
                row.errorCode, List.copyOf(records));
        if (db.updateRun(row, write(next)) != 1) throw new SyncFailure("RUN_CONFLICT");
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
