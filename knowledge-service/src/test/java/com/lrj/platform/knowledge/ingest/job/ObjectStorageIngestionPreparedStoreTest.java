package com.lrj.platform.knowledge.ingest.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static com.lrj.platform.knowledge.ingest.job.PreparedIngestionFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ObjectStorageIngestionPreparedStoreTest {
    private final InMemoryDocumentSourceStore objects = spy(new InMemoryDocumentSourceStore());
    private final ObjectMapper mapper = new ObjectMapper();
    private final IngestionPreparedProperties properties = new IngestionPreparedProperties();

    @Test
    void roundTripsExactTypesVectorsAndTimestampWithoutFollowingNewConfiguration() throws Exception {
        var job = job("roundtrip");
        var document = prepared(job);
        document.segments().getFirst().metadata().put("long", 4L).put("float", 1.25f).put("double", 2.5d)
                .put("int", 3).put("uuid", UUID.randomUUID());
        var ref = store().save(job, document);
        properties.setProcessingVersion("new-model-new-chunking");
        var loaded = store().load(job, ref);
        assertThat(loaded).isEqualTo(document);
        document.embeddings().getFirst().vector()[0] = 99f;
        document.segments().getFirst().metadata().put("changed", "after-save");
        assertThat(store().load(job, ref)).isEqualTo(loaded);
        assertThat(loaded.embeddings().getFirst().vector()[0]).isEqualTo(0.125f);
        verify(objects, times(1)).put(any());
    }

    @Test
    void rejectsInvalidIdentityAndVectorsBeforeWriting() {
        var job = job("invalid");
        var document = prepared(job);
        document.segments().getFirst().metadata().put("tenantId", "other-tenant");
        assertError(() -> store().save(job, document), PreparedResultError.INVALID);
        document.segments().getFirst().metadata().put("tenantId", job.tenantId());
        document.embeddings().getFirst().vector()[0] = Float.NaN;
        assertError(() -> store().save(job, document), PreparedResultError.INVALID);
        verifyNoInteractions(objects);
    }

    @Test
    void boundsBothSerializationAndReadingBeforeAcceptingAnyResult() throws Exception {
        var job = job("limit");
        var normal = store().save(job, prepared(job));
        properties.setMaxBytes(32);
        clearInvocations(objects);
        assertError(() -> store().save(job, prepared(job)), PreparedResultError.LIMIT);
        assertError(() -> store().load(job, normal), PreparedResultError.LIMIT);
        verifyNoInteractions(objects);
        // 引用虚报大小时，仍通过流式读取预算拦截。
        var liar = new IngestionPreparation(1, normal.processingVersion(), new DocumentSourceRef(
                normal.result().bucket(), normal.result().objectKey(), normal.result().contentHash(),
                normal.result().contentType(), 1));
        assertError(() -> store().load(job, liar), PreparedResultError.LIMIT);
    }

    @Test
    void rejectsWrongJobSchemaHashTruncationAndMissingObjects() throws Exception {
        var job = job("integrity");
        var ref = store().save(job, prepared(job));
        assertError(() -> store().load(job("another"), ref), PreparedResultError.INVALID);
        assertError(() -> store().load(job, new IngestionPreparation(99, ref.processingVersion(), ref.result())),
                PreparedResultError.VERSION);
        byte[] bytes;
        try (var input = objects.open(job.tenantId(), ref.result())) { bytes = input.readAllBytes(); }
        bytes[bytes.length / 2] ^= 1;
        doReturn(new ByteArrayInputStream(bytes)).when(objects).open(eq(job.tenantId()), eq(ref.result()));
        assertError(() -> store().load(job, ref), PreparedResultError.INVALID);
        doReturn(new ByteArrayInputStream(new byte[]{1, 2})).when(objects).open(anyString(), any());
        assertError(() -> store().load(job, ref), PreparedResultError.INVALID);
        doThrow(new IOException("private endpoint")).when(objects).open(anyString(), any());
        assertError(() -> store().load(job, ref), PreparedResultError.UNAVAILABLE);
    }

    @Test
    void validatesPayloadIdentityEvenWhenObjectHashIsValid() throws Exception {
        var job = job("context");
        var ref = store().save(job, prepared(job));
        ObjectNode snapshot;
        try (var input = objects.open(job.tenantId(), ref.result())) { snapshot = (ObjectNode) mapper.readTree(input); }
        for (String field : new String[]{"tenantId", "jobId", "documentId", "sourceHash", "processingVersion"}) {
            ObjectNode changed = snapshot.deepCopy().put(field, "mismatch");
            var tampered = put(job, changed, ref.processingVersion());
            assertError(() -> store().load(job, tampered), PreparedResultError.INVALID);
        }
        assertError(() -> store().load(job, put(job, snapshot.deepCopy().put("schemaVersion", 7), ref.processingVersion())),
                PreparedResultError.VERSION);
    }

    private IngestionPreparation put(IngestionJob job, ObjectNode value, String version) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(value);
        var ref = objects.put(new DocumentSourceStore.PutSource(job.tenantId(), job.jobId(), job.documentVersion(),
                ObjectStorageIngestionPreparedStore.CONTENT_TYPE, bytes.length,
                "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                new ByteArrayInputStream(bytes)));
        return new IngestionPreparation(1, version, ref);
    }
    private ObjectStorageIngestionPreparedStore store() {
        return new ObjectStorageIngestionPreparedStore(objects, mapper, properties, Map.of("strategy", "recursive"));
    }
    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, PreparedResultError error) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PreparedResultException.class,
                exception -> assertThat(exception.error()).isEqualTo(error));
    }
}
