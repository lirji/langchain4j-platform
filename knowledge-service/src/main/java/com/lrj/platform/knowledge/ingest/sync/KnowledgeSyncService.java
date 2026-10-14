package com.lrj.platform.knowledge.ingest.sync;

import com.lrj.platform.knowledge.ingest.job.*;
import com.lrj.platform.knowledge.ingest.sync.mapper.SyncRow;
import com.lrj.platform.knowledge.lifecycle.DocumentRegistry;
import com.lrj.platform.security.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static com.lrj.platform.knowledge.ingest.sync.SyncData.*;
import static com.lrj.platform.protocol.knowledge.KnowledgeSourceFeed.*;

/** 按来源串行消费有界增量页；检查点以入库READY为准，源故障不会清空或删除现有知识。 */
public final class KnowledgeSyncService {
    private final KnowledgeSyncStore store;
    private final HttpKnowledgeSource client;
    private final IngestionSubmissionService submissions;
    private final DocumentRegistry registry;
    private final com.lrj.platform.knowledge.authz.KnowledgeAuthz authorization;
    private final com.lrj.platform.audit.AuditLogger audit;
    public KnowledgeSyncService(KnowledgeSyncStore store, HttpKnowledgeSource client,
                                IngestionSubmissionService submissions, DocumentRegistry registry,
                                com.lrj.platform.knowledge.authz.KnowledgeAuthz authorization, com.lrj.platform.audit.AuditLogger audit) {
        this.store = store; this.client = client; this.submissions = submissions; this.registry = registry; this.authorization = authorization; this.audit = audit;
    }
    /** 每次最多拉取一页；其他实例持有租约时跳过，避免多实例重复拉取和提交。 */
    public void poll(KnowledgeSyncProperties.Source source) {
        SyncRow row = store.claim(source);
        if (row == null) return;
        var previous = TenantContext.captureRaw();
        TenantContext.set(new TenantContext.Tenant(source.tenantId(), source.userId(), Set.of("ingest"), source.department()));
        try {
            Pending pending = store.pending(row);
            if (pending == null) {
                Page page = client.fetch(source, row.cursorValue);
                pending = plan(source, row, page);
                store.stage(row, pending);
            }
            while (pending.index() < pending.items().size()) {
                Planned item = pending.items().get(pending.index());
                SyncRow prior = store.document(row, item.item().id());
                if (item.item().deleted()) {
                    store.stage(row, pending);
                    retire(source, item);
                    record(source, item);
                    store.completed(row, item, pending.advance());
                    pending = pending.advance(); continue;
                }
                if (prior != null && !prior.sourceDeleted && item.hash().equals(prior.contentHash)) {
                    if (registry.get(source.tenantId(), item.documentId())
                            .map(info -> (long) info.version() != item.documentVersion()).orElse(true))
                        throw new SyncFailure("DOCUMENT_VERSION_CHANGED");
                    // 来源版本变化但知识内容未变化，只推进映射，不重复调用embedding。
                    store.completed(row, item, pending.advance());
                    pending = pending.advance(); continue;
                }
                // 在副作用前续租并检查CAS；旧执行器只能重放同一固定的入库意图。
                store.stage(row, pending);
                IngestionJob job = submissions.submit(new IngestionSubmissionService.SubmitCommand(
                        jobKey(source, item), source.tenantId(), source.userId(), Set.of("ingest"), source.department(), null,
                        item.documentId(), item.item().title().trim(), null, item.documentVersion(), "text/plain",
                        item.item().content().trim().getBytes(StandardCharsets.UTF_8)));
                if (job.status() != IngestionStatus.READY) {
                    String error = switch (job.status()) {
                        case FAILED, PARTIAL, MANUAL_REVIEW, DELETING, DELETED -> "INGESTION_REQUIRES_ATTENTION";
                        default -> null;
                    };
                    store.release(row, error, job.jobId()); return;
                }
                if (registry.get(source.tenantId(), item.documentId())
                        .map(info -> (long) info.version() != item.documentVersion()).orElse(true))
                    throw new SyncFailure("DOCUMENT_VERSION_CHANGED");
                record(source, item);
                store.completed(row, item, pending.advance());
                pending = pending.advance();
            }
            store.commit(row, pending.nextCursor());
        } catch (Exception failure) {
            String code = failure instanceof SyncFailure known ? known.code()
                    : failure instanceof IngestionAuthorizationException ? "INGESTION_FORBIDDEN"
                    : failure instanceof IngestionJobConflictException ? "INGESTION_CONFLICT" : "SYNC_UNAVAILABLE";
            // 存储错误或失去租约时不能覆盖其他执行器；保留之前已落盘的恢复意图。
            try { store.release(row, code, row.jobId); } catch (RuntimeException ignored) { /* 下轮重读权威状态。 */ }
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
        }
    }
    private Pending plan(KnowledgeSyncProperties.Source source, SyncRow row, Page page) {
        HttpKnowledgeSource.validate(page, row.cursorValue);
        var planned = new ArrayList<Planned>();
        for (Item raw : page.items()) {
            Item item = new Item(raw.id(), raw.revision(), raw.deleted() ? "" : raw.title().trim(),
                    raw.deleted() ? "" : raw.content().trim(), raw.deleted());
            String hash = store.fingerprint(List.of(item.deleted(), item.title(), item.content()));
            SyncRow prior = store.document(row, item.id());
            if (prior != null && item.revision() <= prior.sourceRevision) {
                if (item.revision() == prior.sourceRevision && !hash.equals(prior.contentHash))
                    throw new SyncFailure("SOURCE_REVISION_REUSED");
                continue;
            }
            // 来源记录删除后重新出现时使用新生命周期ID，旧worker或旧删除重放不能影响重建文档。
            boolean create = prior == null || prior.sourceDeleted && !item.deleted();
            String documentId = create ? "http-" + store.fingerprint(List.of(source.tenantId(), source.id(), item.id(), item.revision()))
                    : prior.documentId;
            var registered = registry.get(source.tenantId(), documentId);
            if (create && registered.isPresent() || !create && !prior.sourceDeleted && (registered.isEmpty()
                    || registered.get().version() != prior.documentVersion)) throw new SyncFailure("DOCUMENT_VERSION_CHANGED");
            long version = item.deleted() ? (prior == null ? 0 : prior.documentVersion)
                    : create ? 1 : prior.documentVersion + (hash.equals(prior.contentHash) ? 0 : 1);
            if (version > Integer.MAX_VALUE) throw new SyncFailure("DOCUMENT_VERSION_EXHAUSTED");
            planned.add(new Planned(item, documentId, version, hash));
        }
        return new Pending(List.copyOf(planned), page.nextCursor(), 0);
    }
    /** 先检查编辑权再原子退役；重试看到墓碑后只重放撤权，不能重新开放文档。 */
    private void retire(KnowledgeSyncProperties.Source source, Planned item) {
        if (item.documentVersion() == 0) return;
        var visible = registry.get(source.tenantId(), item.documentId());
        if (visible.isPresent() && !authorization.checkDocument(source.tenantId(), source.userId(), item.documentId(), "edit"))
            throw new SyncFailure("INGESTION_FORBIDDEN");
        if (!registry.retireVersion(source.tenantId(), item.documentId(), item.documentVersion()))
            throw new SyncFailure("DOCUMENT_VERSION_CHANGED");
        // 撤权失败仍保留恢复页；Registry墓碑已阻止检索，后续轮询继续撤权而不重新判已撤销的edit关系。
        authorization.onDocumentDeleted(source.tenantId(), item.documentId());
    }
    /** 沿用平台审计输出，不记录正文、URL或令牌；恢复重放允许重复事件，以来源版本关联。 */
    private void record(KnowledgeSyncProperties.Source source, Planned item) {
        audit.record(item.item().deleted() ? com.lrj.platform.audit.AuditEventType.DOCUMENT_DELETED
                : com.lrj.platform.audit.AuditEventType.DOCUMENT_UPLOADED,
                java.util.Map.of("sourceId", source.id(), "sourceRevision", item.item().revision(),
                        "docId", item.documentId(), "documentVersion", item.documentVersion(), "origin", "http-sync"));
    }
    /** 幂等键绑定来源、外部记录与来源版本；失败后不能换键新建同一任务。 */
    private String jobKey(KnowledgeSyncProperties.Source source, Planned item) {
        return "http-sync-" + store.fingerprint(List.of(source.tenantId(), source.id(), item.item().id(), item.item().revision()));
    }
    /** 用来源记录标识追溯已成功的知识文档，不返回原文或外部凭据。 */
    public DocumentState document(KnowledgeSyncProperties.Source source, String externalId) {
        if (externalId == null || externalId.isBlank() || externalId.length() > 256)
            throw new IllegalArgumentException("invalid external record ID");
        SyncRow checkpoint = store.get(source);
        SyncRow row = checkpoint == null ? null : store.document(checkpoint, externalId);
        if (row == null) return null;
        return new DocumentState(source.id(), row.externalId, row.sourceRevision, row.documentId, row.documentVersion, row.sourceDeleted);
    }
    /** 状态不返回上游地址、游标、正文或凭据。 */
    public Status status(KnowledgeSyncProperties.Source source) {
        SyncRow row = store.get(source);
        if (row == null) return new Status(source.id(), "NOT_STARTED", null, 0, null, 0);
        Pending pending = store.pending(row);
        String error = store.configHash(source).equals(row.configHash) ? row.errorCode : "SOURCE_CONFIG_CHANGED";
        return new Status(source.id(), error != null ? "BLOCKED" : pending != null ? "SYNCING" : "IDLE",
                error, pending == null ? 0 : pending.items().size() - pending.index(), row.jobId, row.nextPollAt);
    }
}
