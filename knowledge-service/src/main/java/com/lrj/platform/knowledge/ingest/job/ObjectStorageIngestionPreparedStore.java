package com.lrj.platform.knowledge.ingest.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 最终文本与向量的不可变对象快照。持久化格式独立于 LangChain4j，对象写入不代表已获得执行权，
 * worker 必须在租约下 CAS 绑定引用后才可开始索引副作用。
 */
public final class ObjectStorageIngestionPreparedStore implements IngestionPreparedStore {
    public static final String CONTENT_TYPE = "application/vnd.knowledge.prepared+json";
    private final DocumentSourceStore objects;
    private final ObjectMapper mapper;
    private final int maxBytes;
    private final String processingVersion;
    private final Map<String, String> profile;

    public ObjectStorageIngestionPreparedStore(DocumentSourceStore objects, ObjectMapper mapper,
                                               IngestionPreparedProperties properties,
                                               Map<String, String> profile) {
        this.objects = Objects.requireNonNull(objects);
        this.mapper = mapper.copy().registerModule(new JavaTimeModule());
        this.maxBytes = properties.getMaxBytes();
        this.processingVersion = properties.getProcessingVersion();
        this.profile = Map.copyOf(profile);
    }

    /** 内容寻址避免旧执行者覆盖新结果；失败/未知提交不删除对象，以免破坏已绑定引用。 */
    @Override
    public IngestionPreparation save(IngestionJob job, PreparedIngestionDocument prepared) {
        try {
            validate(job, prepared);
            List<Block> blocks = prepared.segments().stream().map(segment -> {
                Map<String, Value> metadata = new LinkedHashMap<>();
                segment.metadata().toMap().forEach((key, value) -> metadata.put(key, Value.encode(value)));
                return new Block(segment.text(), metadata);
            }).toList();
            var snapshot = new Snapshot(IngestionPreparation.CURRENT_SCHEMA, processingVersion, profile,
                    job.tenantId(), job.jobId(), job.documentId(), job.documentVersion(),
                    job.source().contentHash(), prepared.info(), blocks,
                    prepared.embeddings().stream().map(Embedding::vector).toList());
            var output = new LimitedOutput(maxBytes);
            mapper.writeValue(output, snapshot);
            byte[] bytes = output.toByteArray();
            var ref = objects.put(new DocumentSourceStore.PutSource(job.tenantId(), job.jobId(),
                    job.documentVersion(), CONTENT_TYPE, bytes.length, hash(bytes), new ByteArrayInputStream(bytes)));
            return new IngestionPreparation(IngestionPreparation.CURRENT_SCHEMA, processingVersion, ref);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw classified(ex);
        } catch (IOException ex) {
            throw new PreparedResultException(PreparedResultError.UNAVAILABLE, ex);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new PreparedResultException(PreparedResultError.INVALID, ex);
        }
    }

    /** 已绑定对象只能读取；不存在或损坏均不回退到重新准备，防止不同 sink 混用两份结果。 */
    @Override
    public PreparedIngestionDocument load(IngestionJob job, IngestionPreparation preparation) {
        if (preparation == null || !preparation.bound()) throw invalid();
        if (preparation.schemaVersion() != IngestionPreparation.CURRENT_SCHEMA) {
            throw new PreparedResultException(PreparedResultError.VERSION);
        }
        DocumentSourceRef ref = preparation.result();
        if (ref.size() > maxBytes) throw new PreparedResultException(PreparedResultError.LIMIT);
        byte[] bytes;
        try (var input = objects.open(job.tenantId(), ref)) {
            bytes = input.readNBytes(maxBytes + 1);
        } catch (IOException ex) {
            throw new PreparedResultException(PreparedResultError.UNAVAILABLE, ex);
        } catch (SecurityException ex) {
            throw new PreparedResultException(PreparedResultError.INVALID, ex);
        }
        if (bytes.length > maxBytes) throw new PreparedResultException(PreparedResultError.LIMIT);
        if (bytes.length != ref.size() || !hash(bytes).equals(ref.contentHash())
                || !CONTENT_TYPE.equals(ref.contentType())) throw invalid();
        try {
            Snapshot snapshot = mapper.readValue(bytes, Snapshot.class);
            if (snapshot.schemaVersion() != IngestionPreparation.CURRENT_SCHEMA) {
                throw new PreparedResultException(PreparedResultError.VERSION);
            }
            if (!Objects.equals(snapshot.processingVersion(), preparation.processingVersion())
                    || !Objects.equals(snapshot.tenantId(), job.tenantId())
                    || !Objects.equals(snapshot.jobId(), job.jobId())
                    || !Objects.equals(snapshot.documentId(), job.documentId())
                    || snapshot.documentVersion() != job.documentVersion()
                    || !Objects.equals(snapshot.sourceHash(), job.source().contentHash())) throw invalid();
            var segments = snapshot.blocks().stream().map(block -> {
                Map<String, Object> metadata = new LinkedHashMap<>();
                block.metadata().forEach((key, value) -> metadata.put(key, value.decode()));
                return TextSegment.from(block.text(), Metadata.from(metadata));
            }).toList();
            var prepared = new PreparedIngestionDocument(snapshot.info(), segments,
                    snapshot.embeddings().stream().map(Embedding::from).toList());
            validate(job, prepared);
            return prepared;
        } catch (IOException | IllegalArgumentException | NullPointerException ex) {
            throw new PreparedResultException(PreparedResultError.INVALID, ex);
        }
    }

    private static void validate(IngestionJob job, PreparedIngestionDocument prepared) {
        if (prepared == null) throw invalid();
        DocumentInfo info = prepared.info();
        if (!job.tenantId().equals(info.tenantId()) || !job.documentId().equals(info.docId())
                || job.documentVersion() != info.version() || info.deleted()
                || !job.displayName().equals(info.displayName())
                || info.sizeBytes() != job.source().size()
                || !Objects.equals(info.contentType(), job.source().contentType())
                || !Objects.equals(info.category(), job.category())
                || info.uploadedAt() == null || info.segmentCount() != prepared.segments().size()) throw invalid();
        int dimension = prepared.embeddings().getFirst().dimension();
        if (dimension <= 0) throw invalid();
        for (int index = 0; index < prepared.segments().size(); index++) {
            var segment = prepared.segments().get(index);
            if (segment.text() == null || segment.text().isBlank()) throw invalid();
            // 兼容不含可选 metadata 的输入，但存在的隔离/索引字段必须与快照身份一致。
            checkMetadata(segment, "tenantId", job.tenantId());
            checkMetadata(segment, "docId", job.documentId());
            checkMetadata(segment, "version", Long.toString(job.documentVersion()));
            checkMetadata(segment, "index", Integer.toString(index));
            var vector = prepared.embeddings().get(index).vector();
            if (vector.length != dimension) throw invalid();
            for (float value : vector) if (!Float.isFinite(value)) throw invalid();
        }
    }

    private static void checkMetadata(TextSegment segment, String key, String expected) {
        Object value = segment.metadata().toMap().get(key);
        if (value != null && !expected.equals(value.toString())) throw invalid();
    }

    private static String hash(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static PreparedResultException invalid() { return new PreparedResultException(PreparedResultError.INVALID); }

    private static PreparedResultException classified(Exception failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PreparedResultException prepared) return prepared;
        }
        return new PreparedResultException(PreparedResultError.INVALID, failure);
    }

    /** 稳定协议 DTO；显式数值类型防止 JSON 往返把 long/float 或 UUID 改为另一种类型。 */
    record Snapshot(int schemaVersion, String processingVersion, Map<String, String> profile,
                    String tenantId, String jobId, String documentId, long documentVersion,
                    String sourceHash, DocumentInfo info, List<Block> blocks, List<float[]> embeddings) { }
    record Block(String text, Map<String, Value> metadata) { }
    record Value(String type, String value) {
        static Value encode(Object value) {
            String type = switch (value) {
                case String ignored -> "string";
                case UUID ignored -> "uuid";
                case Integer ignored -> "int";
                case Long ignored -> "long";
                case Float number when Float.isFinite(number) -> "float";
                case Double number when Double.isFinite(number) -> "double";
                default -> throw invalid();
            };
            return new Value(type, value.toString());
        }
        Object decode() {
            Object result = switch (type) {
                case "string" -> value;
                case "uuid" -> UUID.fromString(value);
                case "int" -> Integer.valueOf(value);
                case "long" -> Long.valueOf(value);
                case "float" -> Float.valueOf(value);
                case "double" -> Double.valueOf(value);
                default -> throw invalid();
            };
            encode(result);
            return result;
        }
    }

    /** 在增长缓冲区之前检查预算，不能先生成无限制 byte[] 再判断大小。 */
    private static final class LimitedOutput extends ByteArrayOutputStream {
        private final int limit;
        LimitedOutput(int limit) { super(Math.min(limit, 8192)); this.limit = limit; }
        @Override public synchronized void write(int value) { check(1); super.write(value); }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            check(length); super.write(bytes, offset, length);
        }
        private void check(int length) {
            if (length > limit - count) throw new PreparedResultException(PreparedResultError.LIMIT);
        }
    }
}
