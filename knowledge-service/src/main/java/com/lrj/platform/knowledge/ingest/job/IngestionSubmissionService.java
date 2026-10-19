package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.knowledge.authz.AuthzMode;
import com.lrj.platform.knowledge.authz.KnowledgeAuthz;
import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.DocumentRegistry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * ingest-api 的窄应用服务：先把原文落权威对象存储，再幂等创建 durable job。
 * 不执行解析、embedding 或索引副作用。
 */
public class IngestionSubmissionService {

    private final DocumentSourceStore sources;
    private final IngestionJobStore jobs;
    private final DocumentRegistry registry;
    private final KnowledgeAuthz authorization;
    private final Clock clock;
    private final Set<IngestionSink> enabledSinks;
    private final IngestionUploadStager uploads;
    private final IngestionResourceProperties limits;

    public IngestionSubmissionService(
            DocumentSourceStore sources,
            IngestionJobStore jobs,
            DocumentRegistry registry,
            KnowledgeAuthz authorization,
            Clock clock,
            Set<IngestionSink> enabledSinks
    ) {
        this(sources,jobs,registry,authorization,clock,enabledSinks,new IngestionResourceProperties());
    }

    /** 生产注入资源预算；旧编程式构造保持相同默认行为。 */
    public IngestionSubmissionService(DocumentSourceStore sources, IngestionJobStore jobs, DocumentRegistry registry,
                                      KnowledgeAuthz authorization, Clock clock, Set<IngestionSink> enabledSinks,
                                      IngestionResourceProperties limits) {
        this.uploads = new IngestionUploadStager(limits);
        this.limits = limits;
        this.sources = Objects.requireNonNull(sources);
        this.jobs = Objects.requireNonNull(jobs);
        this.registry = Objects.requireNonNull(registry);
        this.authorization = Objects.requireNonNull(authorization);
        this.clock = Objects.requireNonNull(clock);
        this.enabledSinks = Set.copyOf(enabledSinks);
        if (this.enabledSinks.stream().noneMatch(IngestionSink::requiredByDefault)) {
            throw new IllegalArgumentException("at least one required sink must be enabled");
        }
    }

    /** 受控数据源/审核发布按外部记录与发布意图管理身份，不把相同正文的独立来源自动合并。 */
    public IngestionJob submit(SubmitCommand command) throws IOException {
        Objects.requireNonNull(command, "command");
        if (command.content == null || command.content.length == 0)
            throw new IngestionResourceException(IngestionResourceError.INPUT_SIZE);
        return submitStream(command.request(), new ByteArrayInputStream(command.content), command.content.length, false);
    }

    /** 输入边读边哈希后以可重开文件落对象存储，调用方持有并关闭原始输入流。 */
    public IngestionJob submitStream(SubmitRequest command, java.io.InputStream input, long declaredSize) throws IOException {
        return submitStream(command, input, declaredSize, true);
    }

    private IngestionJob submitStream(SubmitRequest command, java.io.InputStream input, long declaredSize,
                                      boolean deduplicateContent) throws IOException {
        validate(command);
        var existing = jobs.findByIdempotency(command.tenantId(), command.idempotencyKey());
        if (existing.isPresent()) requireOwner(existing.get(), command.userId());
        DocumentInfo registered = registry.get(command.tenantId(), command.documentId()).orElse(null);
        if (existing.isEmpty()) validateVersionAndAuthorization(command, registered);
        try (var staged = uploads.stage(command.tenantId(), input, declaredSize)) {
            String contentHash = staged.hash();
            if (existing.isPresent()) {
                validateReplay(command,existing.get(),contentHash); return existing.get();
            }
            IngestionJob candidate = null;
            for (int attempt = 0; attempt < limits.getMaxContentionRetries(); attempt++) {
                // 竞争者可能使用同一提交编号；幂等回放必须优先于内容重复提示。
                var replay = jobs.findByIdempotency(command.tenantId(), command.idempotencyKey());
                if (replay.isPresent()) {
                    validateReplay(command, replay.get(), contentHash);
                    return replay.get();
                }
                var snapshot = deduplicateContent
                        ? jobs.contentSnapshot(command.tenantId(), contentHash, limits.getMaxContentCandidates()) : null;
                if (snapshot != null) {
                    for (var match : snapshot.matches()) {
                        if (match.idempotencyKey().equals(command.idempotencyKey())) {
                            validateReplay(command, match, contentHash);
                            return match;
                        }
                    }
                    var readable = authorization.filterReadable(command.tenantId(), command.userId(),
                            snapshot.matches().stream().map(IngestionJob::documentId).collect(java.util.stream.Collectors.toSet()));
                    IngestionDuplicateException duplicate = null;
                    for (var match : snapshot.matches()) {
                        var found = duplicateFor(command, match, readable);
                        if (found != null && (duplicate == null || duplicatePriority(found) > duplicatePriority(duplicate))) duplicate = found;
                    }
                    if (duplicate != null) throw duplicate;
                    if (snapshot.truncated()) throw new IngestionResourceException(IngestionResourceError.DEDUPLICATION_BUSY);
                }
                if (candidate == null) {
                    DocumentSourceRef source = sources.putFile(new DocumentSourceStore.SourceDescriptor(
                            command.tenantId(), command.documentId(), command.documentVersion(), command.contentType(),
                            staged.size(), contentHash), staged.path());
                    candidate = IngestionJob.received(UUID.randomUUID().toString(), command.idempotencyKey(),
                            command.tenantId(), command.userId(), command.scopes(), command.department(), command.traceId(),
                            command.documentId(), command.displayName(), command.category(), command.documentVersion(),
                            registered == null, source, enabledSinks, clock.instant());
                }
                // 源文件 I/O、注册表和授权均在锁外；事务只验证快照并创建任务。
                var result = snapshot == null ? java.util.Optional.of(jobs.createOrGet(candidate))
                        : jobs.createIfContentUnchanged(candidate, snapshot.generation());
                if (result.isPresent()) {
                    validateReplay(command, result.get(), contentHash);
                    return result.get();
                }
            }
            // 数据库结果未知时对象可能已被获胜任务引用，不删除权威原文；客户端沿用原提交编号恢复。
            throw new IngestionResourceException(IngestionResourceError.DEDUPLICATION_BUSY);
        } catch (IOException failure) {
            throw new IngestionResourceException(IngestionResourceError.INPUT_UNAVAILABLE,failure);
        }
    }

    /** 以当前发布版本和真实 view 权限判断重复，不用文件名/部门标签猜测共享范围。 */
    private IngestionDuplicateException duplicateFor(SubmitRequest command, IngestionJob match, Set<String> readable) {
        if (match.status() == IngestionStatus.DELETED) return null;
        var current = registry.get(command.tenantId(), match.documentId()).orElse(null);
        if (current != null && (current.deleted() || current.version() > match.documentVersion())) return null;
        boolean published = current != null && current.version() == match.documentVersion();
        // 历史 READY 任务的文档已移除，不能用任务历史阻止再次上传。
        if (match.status() == IngestionStatus.READY && !published) return null;
        boolean owner = match.userId().equals(command.userId());
        if (!(current == null && owner) && !readable.contains(match.documentId())) return null;
        var kind = published ? IngestionDuplicateException.Kind.READY
                : switch (match.status()) {
                    case RECEIVED, PROCESSING, DELETING -> IngestionDuplicateException.Kind.PROCESSING;
                    default -> IngestionDuplicateException.Kind.ATTENTION;
                };
        return new IngestionDuplicateException(kind, new IngestionDuplicateException.Reference(
                published || owner ? match.documentId() : null,
                published ? current.displayName() : owner ? match.displayName() : null,
                published || owner ? match.documentVersion() : null,
                owner ? match.jobId() : null, published));
    }

    /** 历史副本并存时优先可用文档，其次是本人能恢复的任务，避免被旧失败记录遮住。 */
    private static int duplicatePriority(IngestionDuplicateException duplicate) {
        return (duplicate.duplicate().documentAvailable() ? 2 : 0) + (duplicate.duplicate().jobId() != null ? 1 : 0);
    }

    /** 重放也检查请求指纹与提交者；相同租户不能通过猜测幂等键读取他人任务。 */
    private void validateReplay(SubmitRequest command, IngestionJob existing, String contentHash) {
        requireOwner(existing, command.userId());
        if (!existing.documentId().equals(command.documentId())
                || existing.documentVersion() != command.documentVersion()
                || !existing.displayName().equals(command.displayName())
                || !Objects.equals(existing.category(), command.category())
                || !existing.source().contentType().equals(command.contentType())
                || !existing.source().contentHash().equals(contentHash)) {
            throw new IngestionJobConflictException("idempotency key was already used with a different request");
        }
    }

    /** 任务详情属于提交者；不存在与跨用户访问均返回 404，避免泄露任务存在性。 */
    public IngestionJob get(String tenantId, String userId, String jobId) {
        IngestionJob job = get(tenantId, jobId);
        requireOwner(job, userId);
        return job;
    }

    /** 提交者显式恢复已失败任务；先重新检查 ingest 与文档编辑权限，再用 revision 竞争恢复权。 */
    public IngestionJob retry(String tenantId, String userId, Set<String> scopes, String jobId) {
        if (!scopes.contains("ingest")) {
            throw new IngestionAuthorizationException("ingest scope required");
        }
        IngestionJob job = get(tenantId, userId, jobId);
        if (registry.get(tenantId, job.documentId()).isPresent()
                && !authorization.checkDocument(tenantId, userId, job.documentId(), "edit")) {
            throw new IngestionAuthorizationException("edit permission required");
        }
        if (job.status() != IngestionStatus.MANUAL_REVIEW && job.status() != IngestionStatus.PARTIAL
                && job.status() != IngestionStatus.FAILED) {
            throw new IngestionJobConflictException("only failed jobs can be retried");
        }
        return jobs.save(IngestionJobStateMachine.retry(job, clock.instant())
                .withExecution(IngestionExecution.EMPTY), job.revision());
    }

    private static void requireOwner(IngestionJob job, String userId) {
        if (!job.userId().equals(userId)) {
            throw new IngestionJobNotFoundException(job.jobId());
        }
    }

    public IngestionJob get(String tenantId, String jobId) {
        return jobs.find(tenantId, jobId)
                .orElseThrow(() -> new IngestionJobNotFoundException(jobId));
    }

    private static void validate(SubmitRequest command) {
        Objects.requireNonNull(command, "command");
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey and non-empty content are required");
        }
        if (command.tenantId() == null || command.userId() == null || command.documentId() == null
                || command.contentType() == null || !command.scopes().contains("ingest")) {
            throw new IllegalArgumentException("tenant, user, document, content type and ingest scope are required");
        }
        if (command.displayName() == null || command.displayName().isBlank()
                || command.documentVersion() < 1) {
            throw new IllegalArgumentException(
                    "displayName and positive documentVersion are required");
        }
    }

    private void validateVersionAndAuthorization(
            SubmitRequest command,
            DocumentInfo registered
    ) {
        if (registered == null) {
            if (command.documentVersion() != 1) {
                throw new IngestionJobConflictException(
                        "new document must start at version 1");
            }
            if (authorization.mode() == AuthzMode.ENFORCE
                    && (command.department() == null || command.department().isBlank())) {
                throw new IngestionAuthorizationException(
                        "uploader department is required for document creation");
            }
            return;
        }
        if (command.documentVersion() != registered.version() + 1L) {
            throw new IngestionJobConflictException(
                    "documentVersion must advance the registered version by one");
        }
        if (!authorization.checkDocument(
                command.tenantId(), command.userId(), command.documentId(), "edit")) {
            throw new IngestionAuthorizationException(
                    "edit permission is required to replace document");
        }
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 不携带原文字节的提交元数据，所有身份仍由认证入口提供。 */
    public record SubmitRequest(String idempotencyKey, String tenantId, String userId, Set<String> scopes,
                                String department, String traceId, String documentId, String displayName, String category,
                                long documentVersion, String contentType) {
        public SubmitRequest {
            idempotencyKey=normalized(idempotencyKey);tenantId=normalized(tenantId);userId=normalized(userId);
            documentId=normalized(documentId);displayName=normalized(displayName);category=normalized(category);
            department=normalized(department);contentType=normalized(contentType);scopes=Set.copyOf(scopes);
            traceId=traceId==null || traceId.isBlank()?UUID.randomUUID().toString():traceId;
        }
    }

    public record SubmitCommand(
            String idempotencyKey,
            String tenantId,
            String userId,
            Set<String> scopes,
            String department,
            String traceId,
            String documentId,
            String displayName,
            String category,
            long documentVersion,
            String contentType,
            byte[] content
    ) {
        public SubmitCommand {
            idempotencyKey = normalized(idempotencyKey);
            tenantId = normalized(tenantId);
            userId = normalized(userId);
            documentId = normalized(documentId);
            displayName = normalized(displayName);
            category = normalized(category);
            department = normalized(department);
            contentType = normalized(contentType);
            traceId = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
            scopes = Set.copyOf(scopes);
            content = content == null ? null : content.clone();
        }

        /** 复用已经复制的身份字段，不通过 content() 再复制大字节数组。 */
        public SubmitRequest request() {
            return new SubmitRequest(idempotencyKey,tenantId,userId,scopes,department,traceId,documentId,displayName,category,documentVersion,contentType);
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }
}
