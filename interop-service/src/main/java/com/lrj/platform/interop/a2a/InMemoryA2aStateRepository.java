package com.lrj.platform.interop.a2a;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Single-process local/test repository with the same revision CAS semantics as Redis. */
public class InMemoryA2aStateRepository implements A2aStateRepository {

    private final ConcurrentMap<String, A2aTaskContextRecord> records = new ConcurrentHashMap<>();

    @Override
    public Optional<A2aTaskContextRecord> get(String tenantId, String taskId) {
        A2aTaskContextRecord record = records.get(key(tenantId, taskId));
        if (record != null && record.expiresAt().isBefore(Instant.now())) {
            records.remove(key(tenantId, taskId), record);
            return Optional.empty();
        }
        return Optional.ofNullable(record);
    }

    @Override
    public synchronized boolean compareAndSet(A2aTaskContextRecord record, Long expectedRevision) {
        String key = key(record.tenantId(), record.taskId());
        A2aTaskContextRecord current = records.get(key);
        if (expectedRevision == null) {
            if (current != null) {
                return false;
            }
        } else if (current == null || current.revision() != expectedRevision) {
            return false;
        }
        records.put(key, record);
        return true;
    }

    private static String key(String tenantId, String taskId) {
        return (tenantId == null ? "" : tenantId) + "::" + taskId;
    }
}
