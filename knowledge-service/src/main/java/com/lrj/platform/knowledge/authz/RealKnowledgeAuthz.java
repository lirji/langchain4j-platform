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

    private final AuthzEngine engine;
    private final AuthzMode mode;
    /** 可选判权指标 (Micrometer); null 时仅日志。 */
    private final MeterRegistry meter;

    /** 默认 ENFORCE（供集成测试与显式强制场景; 生产由 {@link KnowledgeAuthzConfig} 按 app.rag.authz.mode 注入）。 */
    public RealKnowledgeAuthz(AuthzEngine engine) {
        this(engine, AuthzMode.ENFORCE);
    }

    public RealKnowledgeAuthz(AuthzEngine engine, AuthzMode mode) {
        this(engine, mode, null);
    }

    public RealKnowledgeAuthz(AuthzEngine engine, AuthzMode mode, MeterRegistry meter) {
        this.engine = engine;
        this.mode = mode == null ? AuthzMode.ENFORCE : mode;
        this.meter = meter;
    }

    @Override
    public AuthzMode mode() {
        return mode;
    }

    @Override
    public void onDocumentCreated(String tenantId, String docId, String ownerUserId) {
        ResourceRef doc = ResourceRef.of("document", key(tenantId, docId));
        List<RelationshipUpdate> updates = List.of(
                // owner 用 CREATE（非 TOUCH）：并发/重复新建时 SpiceDB 拒绝第二个 owner，防止 owner 累积/被接管。
                RelationshipUpdate.create(doc, "owner", SubjectRef.user(ownerUserId)),
                RelationshipUpdate.touch(doc, "parent_space",
                        SubjectRef.of("space", KnowledgeResourceIds.space(tenantId, DEFAULT_SPACE))));
        engine.writeRelationships(updates);
        log.debug("authz: document {} owner={} 写入", doc.ref(), ownerUserId);
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
        List<ResourceRef> resources = docIds.stream()
                .map(d -> ResourceRef.of("document", key(tenantId, d)))
                .toList();
        Map<ResourceRef, Boolean> allowed;
        try {
            // 首期强一致：多副本下刚写入的授权/撤权立即可见，避免单进程水位在多实例间失效。
            allowed = engine.checkBulk(SubjectRef.user(userId), "view", resources, Consistency.fullyConsistent());
        } catch (RuntimeException e) {
            log.warn("authz dependency error mode={} op=filter: {}", mode, e.toString());
            recordError("filter", "view");
            // 判权依赖故障：shadow 放行全集(fail-open + 观测)；enforce 拒绝全部(fail-closed)。
            return mode == AuthzMode.SHADOW ? docIds : Set.of();
        }
        Set<String> readable = new LinkedHashSet<>();
        for (String d : docIds) {
            boolean ok = Boolean.TRUE.equals(allowed.get(ResourceRef.of("document", key(tenantId, d))));
            record("filter", "view", ok);
            if (ok) {
                readable.add(d);
            }
        }
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

    private static String key(String tenantId, String id) {
        return KnowledgeResourceIds.document(tenantId, id);
    }
}
