package com.lrj.platform.knowledge.authz;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.RelationshipFilter;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 接 auth-platform 的真实实现: 经 SDK 的 {@link AuthzEngine} (HTTP -> auth-platform-server) 判权。
 * <ul>
 *   <li>写: 文档建/删时双写 SpiceDB (document:&lt;tid&gt;_&lt;docId&gt; 的 owner + parent_space)。</li>
 *   <li>读: checkBulk/check(view/edit) 过滤候选。首期用 {@link Consistency#fullyConsistent()} 保证多副本下
 *       刚授权/撤权立即生效 —— 不依赖单进程 ZedToken 水位 (副本 A 写、副本 B 读不到, 多副本不可靠)。</li>
 * </ul>
 * SpiceDB 对象 id 用 &lt;tenantId&gt;_&lt;id&gt; 消歧 (docId 仅 16hex 且含 tenant 派生, 跨租户理论可撞)。
 *
 * <p>{@link AuthzMode}:
 * <ul>
 *   <li>SHADOW —— 照写关系; 读路径真算 ReBAC 可见/许可并打点差异, 但<strong>不拦截</strong>(放行全集/放行操作)。</li>
 *   <li>ENFORCE —— 照写关系; 读路径按 ReBAC 真过滤/真拦。</li>
 * </ul>
 *
 * <p>观测: 每次判定计一次 Micrometer counter {@code knowledge.authz.decisions}
 * (tags: mode, operation=filter|single, permission, decision=allow|deny)。shadow 下 decision=deny 的计数
 * 即"切 enforce 会被拒绝的量", 供灰度评估。meter 为 null 时仅结构化日志 (测试/无 actuator)。
 */
public class RealKnowledgeAuthz implements KnowledgeAuthz {

    private static final Logger log = LoggerFactory.getLogger(RealKnowledgeAuthz.class);
    /**
     * 默认知识库 (space): 一个租户的文档默认挂在 &lt;tid&gt;_default 下。
     * 注意 default space <strong>无 viewer 元组</strong>，故 enforce 下同租户【不】自动共享，
     * 仅 owner / 被显式授权者可见（见决策 D3）。"同租户共享"需给 default space 绑租户成员组（里程碑 C）。
     */
    private static final String DEFAULT_SPACE = "default";

    /** 单次 checkBulk 资源数默认上限（未显式配置时；配置来自 app.rag.authz.bulk-size）。 */
    private static final int DEFAULT_BULK_SIZE = 100;

    private final AuthzEngine engine;
    private final AuthzMode mode;
    /** 可选判权指标 (Micrometer); null 时仅日志。 */
    private final MeterRegistry meter;
    /** 单次 checkBulk 的最大资源数, 超出按此分批(稳定顺序)。>=1。 */
    private final int bulkSize;

    /** 默认 ENFORCE（供集成测试与显式强制场景; 生产由 {@link KnowledgeAuthzConfig} 按 app.rag.authz.mode 注入）。 */
    public RealKnowledgeAuthz(AuthzEngine engine) {
        this(engine, AuthzMode.ENFORCE);
    }

    public RealKnowledgeAuthz(AuthzEngine engine, AuthzMode mode) {
        this(engine, mode, null);
    }

    public RealKnowledgeAuthz(AuthzEngine engine, AuthzMode mode, MeterRegistry meter) {
        this(engine, mode, meter, DEFAULT_BULK_SIZE);
    }

    public RealKnowledgeAuthz(AuthzEngine engine, AuthzMode mode, MeterRegistry meter, int bulkSize) {
        this.engine = engine;
        this.mode = mode == null ? AuthzMode.ENFORCE : mode;
        this.meter = meter;
        this.bulkSize = bulkSize < 1 ? DEFAULT_BULK_SIZE : bulkSize;
    }

    @Override
    public AuthzMode mode() {
        return mode;
    }

    @Override
    public void onDocumentCreated(String tenantId, String docId, String ownerUserId, String departmentId) {
        ResourceRef doc = ResourceRef.of("document", key(tenantId, docId));
        List<RelationshipUpdate> updates = new ArrayList<>();
        // owner 用 CREATE（非 TOUCH）：并发/重复新建时 SpiceDB 拒绝第二个 owner，防止 owner 累积/被接管。
        updates.add(RelationshipUpdate.create(doc, "owner", SubjectRef.user(ownerUserId)));
        // 部门层级模型：归属 = 上传人部门（home_dept）。departmentId 为空则不写——enforce 下无部门的上传由上游按 mode 拒绝。
        if (departmentId != null && !departmentId.isBlank()) {
            updates.add(RelationshipUpdate.touch(doc, "home_dept",
                    SubjectRef.of("department", KnowledgeResourceIds.department(tenantId, departmentId))));
        }
        // 兼容窗口：临时保留 parent_space=<t>_default 双写（仅供回滚；不进入新 view，contract 阶段移除）。
        updates.add(RelationshipUpdate.touch(doc, "parent_space",
                SubjectRef.of("space", KnowledgeResourceIds.space(tenantId, DEFAULT_SPACE))));
        engine.writeRelationships(updates);
        log.debug("authz: document {} owner={} dept={} 写入", doc.ref(), ownerUserId, departmentId);
    }

    @Override
    public void onDocumentDeleted(String tenantId, String docId) {
        engine.deleteRelationships(
                RelationshipFilter.ofResource(ResourceRef.of("document", key(tenantId, docId))));
    }

    @Override
    public void grantDocumentViewer(String tenantId, String docId, String userId) {
        engine.writeRelationships(List.of(RelationshipUpdate.touch(
                ResourceRef.of("document", key(tenantId, docId)), "viewer", SubjectRef.user(userId))));
    }

    @Override
    public void revokeDocumentViewer(String tenantId, String docId, String userId) {
        engine.writeRelationships(List.of(RelationshipUpdate.delete(
                ResourceRef.of("document", key(tenantId, docId)), "viewer", SubjectRef.user(userId))));
    }

    @Override
    public boolean checkDocument(String tenantId, String userId, String docId, String permission) {
        boolean allowed;
        try {
            allowed = engine.check(SubjectRef.user(userId), permission,
                    ResourceRef.of("document", key(tenantId, docId)), Consistency.fullyConsistent());
        } catch (RuntimeException e) {
            return onDependencyError("single", permission, e);
        }
        record("single", permission, allowed);
        if (mode == AuthzMode.SHADOW) {
            if (!allowed) {
                log.info("authz shadow tenant={} user={} doc={} perm={} would_deny", tenantId, userId, docId, permission);
            }
            return true;
        }
        return allowed;
    }

    @Override
    public Set<String> filterReadable(String tenantId, String userId, Set<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return docIds;
        }
        List<String> ordered = new ArrayList<>(docIds);
        Map<ResourceRef, Boolean> allowed = new LinkedHashMap<>();
        long startNanos = System.nanoTime();
        try {
            // 首期强一致：多副本下刚写入的授权/撤权立即可见，避免单进程水位在多实例间失效。
            // 候选池有界(KnowledgeQueryService 封顶)，但仍按 bulkSize 分批, 防单请求过大打爆 SpiceDB。
            for (int i = 0; i < ordered.size(); i += bulkSize) {
                List<String> chunk = ordered.subList(i, Math.min(i + bulkSize, ordered.size()));
                List<ResourceRef> resources = chunk.stream()
                        .map(d -> ResourceRef.of("document", key(tenantId, d)))
                        .toList();
                allowed.putAll(engine.checkBulk(SubjectRef.user(userId), "view", resources, Consistency.fullyConsistent()));
            }
        } catch (RuntimeException e) {
            log.warn("authz dependency error mode={} op=filter: {}", mode, e.toString());
            recordError("filter", "view");
            recordCheckBulkLatency(startNanos);
            // 判权依赖故障(含 SDK 响应协议异常)：shadow 放行全集(fail-open + 观测)；enforce 拒绝全部(fail-closed)。
            return mode == AuthzMode.SHADOW ? docIds : Set.of();
        }
        recordCheckBulkLatency(startNanos);
        Set<String> readable = new LinkedHashSet<>();
        for (String d : ordered) {
            boolean ok = Boolean.TRUE.equals(allowed.get(ResourceRef.of("document", key(tenantId, d))));
            record("filter", "view", ok);
            if (ok) {
                readable.add(d);
            }
        }
        recordVolume(docIds.size(), readable.size());
        if (mode == AuthzMode.SHADOW) {
            // 影子模式：只观测、不拦截。返回全集，差异打点供灰度评估 enforce 影响。
            if (readable.size() != docIds.size()) {
                log.info("authz shadow tenant={} user={} candidates={} rebac_readable={} would_filter={}",
                        tenantId, userId, docIds.size(), readable.size(), docIds.size() - readable.size());
            }
            return docIds;
        }
        return readable;
    }

    /** 每次判定计一次 counter；decision=deny 在 shadow 下即"切 enforce 会被拒的量"。 */
    private void record(String operation, String permission, boolean allowed) {
        if (meter == null) {
            return;
        }
        meter.counter("knowledge.authz.decisions",
                "mode", mode.name().toLowerCase(),
                "operation", operation,
                "permission", permission == null ? "view" : permission,
                "decision", allowed ? "allow" : "deny").increment();
    }

    /** 判权依赖故障：shadow 放行(fail-open) + 记 error 指标；enforce 拒绝(fail-closed) + 记 error 指标。 */
    private boolean onDependencyError(String operation, String permission, RuntimeException e) {
        log.warn("authz dependency error mode={} op={} perm={}: {}", mode, operation, permission, e.toString());
        recordError(operation, permission);
        return mode == AuthzMode.SHADOW;
    }

    private void recordError(String operation, String permission) {
        if (meter == null) {
            return;
        }
        meter.counter("knowledge.authz.decisions",
                "mode", mode.name().toLowerCase(),
                "operation", operation,
                "permission", permission == null ? "view" : permission,
                "decision", "error").increment();
    }

    /**
     * 记录一次 filter 判权的容量指标（低基数, 仅 mode tag）：候选数、可读数、underfill（不可读数,
     * 即 enforce 下会被过滤掉的量；shadow 下为"若 enforce 会被过滤的量"）。供灰度评估召回损失。
     */
    private void recordVolume(int candidates, int readable) {
        if (meter == null) {
            return;
        }
        String m = mode.name().toLowerCase();
        meter.counter("knowledge.authz.candidates", "mode", m).increment(candidates);
        meter.counter("knowledge.authz.allowed_docs", "mode", m).increment(readable);
        int underfill = Math.max(0, candidates - readable);
        if (underfill > 0) {
            meter.counter("knowledge.authz.underfill", "mode", m).increment(underfill);
        }
    }

    /** 记录 checkBulk 调用总耗时（含分批, 低基数）。 */
    private void recordCheckBulkLatency(long startNanos) {
        if (meter == null) {
            return;
        }
        meter.timer("knowledge.authz.check_bulk.latency", "mode", mode.name().toLowerCase())
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private static String key(String tenantId, String id) {
        return KnowledgeResourceIds.document(tenantId, id);
    }
}
