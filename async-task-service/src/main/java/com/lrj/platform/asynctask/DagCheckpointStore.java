package com.lrj.platform.asynctask;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.asynctask.mapper.DagCheckpointMapper;
import com.lrj.platform.persistence.PlusMapperFactory;
import com.lrj.platform.protocol.asynctask.DagCheckpoint;
import com.lrj.platform.protocol.asynctask.DagCheckpointRequest;
import com.lrj.platform.security.AsyncTaskWorkerToken;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 中央唯一检查点写入入口；父任务锁覆盖鉴权、租约、CAS和事件提交。 */
@Component
@ConditionalOnProperty(name="app.async-task.dispatch.enabled", havingValue="true")
public final class DagCheckpointStore {
    private final DagCheckpointMapper db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final AsyncTaskEventJournal journal;
    /** 复用中央数据源和事务管理器，不引入独立连接或第二写入权威。 */
    public DagCheckpointStore(DataSource ds, @Qualifier("asyncTaskTransactionManager") PlatformTransactionManager manager,
            ObjectMapper json, AsyncTaskEventJournal journal) {
        this.db = PlusMapperFactory.create(ds, DagCheckpointMapper.class);
        this.tx = new TransactionTemplate(manager);
        this.json = json;
        this.journal = journal;
    }
    /** 读取也校验有效租约，禁止通过旧worker凭据窃取结果。 */
    public DagCheckpoint access(String id, AsyncTaskWorkerToken.Principal actor, DagCheckpointRequest request) {
        if (request == null || request.leaseEpoch() < 1 || request.expectedRevision() != null && request.expectedRevision() < 0)
            throw error(HttpStatus.BAD_REQUEST);
        return tx.execute(status -> {
            var task = db.lockTask(id).stream().findFirst().orElse(null);
            if (task == null || !actor.tenantId().equals(task.tenantId) || !actor.actorUserId().equals(task.userId))
                throw error(HttpStatus.NOT_FOUND);
            if (!Set.of("agent.readonly.dag.v2", "agent.readonly.dag-plan.v2", "agent.readonly.analyst.v2", "agent.readonly.process.v2").contains(task.kind)
                    || !actor.workerId().equals(task.leaseOwnerId) || task.leaseEpoch == null || request.leaseEpoch() != task.leaseEpoch
                    || task.leaseExpiresAt == null || task.leaseExpiresAt <= Instant.now().toEpochMilli()
                    || !Set.of("PENDING", "RUNNING").contains(task.status)) throw error(HttpStatus.CONFLICT);
            var row = db.read(id).stream().findFirst().orElse(null);
            long revision = row == null ? 0 : row.revision;
            Map<String,Object> previous = row == null ? null : decode(row.stateJson);
            if (request.expectedRevision() == null) {
                if (request.state() != null) throw error(HttpStatus.BAD_REQUEST);
                return new DagCheckpoint(revision, previous);
            }
            if (request.expectedRevision() != revision) throw error(HttpStatus.CONFLICT);
            validate(request.state(), previous);
            String encoded = encode(request.state());
            if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2_097_152) throw error(HttpStatus.PAYLOAD_TOO_LARGE);
            long now = Instant.now().toEpochMilli();
            int changed = row == null ? db.insert(id, encoded, now) : db.update(id, revision, encoded, now);
            if (changed != 1) throw error(HttpStatus.CONFLICT);
            journal.append(id, "checkpoint:" + (revision+1), "dag-checkpoint", Map.of("revision", revision+1), actor.workerId(), Instant.ofEpochMilli(now));
            return new DagCheckpoint(revision+1, request.state());
        });
    }
    private void validate(Map<String,Object> state, Map<String,Object> old) {
        if (state == null || !Set.of("schemaVersion","binding","deadline","modelCalls","reservedTokens","operations").equals(state.keySet())
                || number(state, "schemaVersion") != 1 || !(state.get("binding") instanceof String binding) || !binding.matches("[0-9a-f]{64}")
                || number(state,"deadline") <= 0 || number(state,"modelCalls") < 0 || number(state,"reservedTokens") < 0
                || !(state.get("operations") instanceof Map<?,?> ops) || ops.size() > 256) throw error(HttpStatus.BAD_REQUEST);
        if (old != null && (!Objects.equals(old.get("binding"), state.get("binding"))
                || number(old,"deadline") != number(state,"deadline")
                || number(state,"modelCalls") < number(old,"modelCalls")
                || number(state,"reservedTokens") < number(old,"reservedTokens"))) throw error(HttpStatus.CONFLICT);
    }
    private static long number(Map<String,Object> state, String key) {
        Object value = state.get(key);
        if (!(value instanceof Integer || value instanceof Long)) throw error(HttpStatus.BAD_REQUEST);
        return ((Number)value).longValue();
    }
    private String encode(Map<String,Object> state) {
        try { return json.writeValueAsString(state); }
        catch (JsonProcessingException e) { throw error(HttpStatus.BAD_REQUEST); }
    }
    private Map<String,Object> decode(String state) {
        try { return json.readValue(state, new TypeReference<Map<String,Object>>() { }); }
        catch (JsonProcessingException e) { throw new IllegalStateException("invalid stored DAG checkpoint", e); }
    }
    private static ResponseStatusException error(HttpStatus status) { return new ResponseStatusException(status, "DAG checkpoint rejected"); }
}
