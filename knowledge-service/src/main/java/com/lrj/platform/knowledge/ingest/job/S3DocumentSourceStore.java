package com.lrj.platform.knowledge.ingest.job;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;

/** S3-compatible 原文存储生产适配器；bucket/prefix/凭据由 Knowledge worker 配置。 */
public class S3DocumentSourceStore implements DocumentSourceStore {

    private final S3Client client;
    private final String bucket;
    private final String prefix;

    public S3DocumentSourceStore(S3Client client, String bucket, String prefix) {
        this.client = Objects.requireNonNull(client);
        this.bucket = requireText(bucket, "bucket");
        String normalizedPrefix = prefix == null ? "" : prefix.trim();
        this.prefix = normalizedPrefix.isEmpty()
                ? ""
                : normalizedPrefix.replaceAll("^/+|/+$", "") + "/";
    }

    @Override
    public DocumentSourceRef put(PutSource command) throws IOException {
        InMemoryDocumentSourceStore.validateCommand(command);
        String key = prefix + InMemoryDocumentSourceStore.objectKey(command);
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(command.contentType())
                            .metadata(Map.of(
                                    "content-hash", command.contentHash(),
                                    "tenant-id", command.tenantId(),
                                    "document-id", command.documentId(),
                                    "document-version", Long.toString(command.version())))
                            .build(),
                    RequestBody.fromInputStream(command.content(), command.size()));
            return new DocumentSourceRef(
                    bucket, key, command.contentHash(), command.contentType(), command.size());
        } catch (SdkException ex) {
            throw new IOException("failed to store document source", ex);
        }
    }

    @Override
    public InputStream open(String tenantId, DocumentSourceRef source) throws IOException {
        requireOwnedKey(tenantId, source);
        try {
            return client.getObject(GetObjectRequest.builder()
                    .bucket(source.bucket())
                    .key(source.objectKey())
                    .build());
        } catch (NoSuchKeyException ex) {
            throw new IOException("source object not found", ex);
        } catch (SdkException ex) {
            throw new IOException("failed to read document source", ex);
        }
    }

    @Override
    public void delete(String tenantId, DocumentSourceRef source) throws IOException {
        requireOwnedKey(tenantId, source);
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(source.bucket())
                    .key(source.objectKey())
                    .build());
        } catch (SdkException ex) {
            throw new IOException("failed to delete document source", ex);
        }
    }

    private void requireOwnedKey(String tenantId, DocumentSourceRef source) {
        Objects.requireNonNull(source, "source");
        String tenantPrefix = prefix
                + InMemoryDocumentSourceStore.safeSegment(tenantId, "tenantId")
                + "/";
        if (!source.bucket().equals(bucket) || !source.objectKey().startsWith(tenantPrefix)) {
            throw new SecurityException("source object does not belong to tenant");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
