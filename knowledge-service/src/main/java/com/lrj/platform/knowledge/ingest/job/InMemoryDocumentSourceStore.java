package com.lrj.platform.knowledge.ingest.job;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** dev/test adapter。生产拆进程使用 {@link S3DocumentSourceStore}。 */
public class InMemoryDocumentSourceStore implements DocumentSourceStore {

    private final Map<String, byte[]> objects = new LinkedHashMap<>();

    @Override
    public synchronized DocumentSourceRef put(PutSource command) throws IOException {
        validateCommand(command);
        String key = objectKey(command);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        command.content().transferTo(output);
        byte[] bytes = output.toByteArray();
        if (bytes.length != command.size()) {
            throw new IOException("source size mismatch");
        }
        objects.put(tenantKey(command.tenantId(), key), bytes);
        return new DocumentSourceRef(
                "memory", key, command.contentHash(), command.contentType(), command.size());
    }

    @Override
    public synchronized InputStream open(String tenantId, DocumentSourceRef source)
            throws IOException {
        byte[] value = objects.get(tenantKey(tenantId, source.objectKey()));
        if (value == null) {
            throw new IOException("source object not found");
        }
        return new ByteArrayInputStream(value);
    }

    @Override
    public synchronized void delete(String tenantId, DocumentSourceRef source) {
        objects.remove(tenantKey(tenantId, source.objectKey()));
    }

    static String objectKey(PutSource command) {
        return safeSegment(command.tenantId(), "tenantId")
                + "/" + safeSegment(command.documentId(), "documentId")
                + "/v" + command.version()
                + "/" + safeSegment(command.contentHash().replace(':', '-'), "contentHash")
                + "/source";
    }

    static void validateCommand(PutSource command) {
        if (command == null || command.version() < 1 || command.size() < 0
                || command.content() == null || command.contentType() == null
                || command.contentType().isBlank() || command.contentHash() == null
                || command.contentHash().isBlank()) {
            throw new IllegalArgumentException("valid source command is required");
        }
        safeSegment(command.tenantId(), "tenantId");
        safeSegment(command.documentId(), "documentId");
    }

    static String safeSegment(String value, String field) {
        if (value == null || value.isBlank() || value.contains("/")
                || value.contains("\\") || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException(field + " must be a safe object-key segment");
        }
        return value.trim();
    }

    private static String tenantKey(String tenantId, String objectKey) {
        return safeSegment(tenantId, "tenantId") + '\u0000' + objectKey;
    }
}
