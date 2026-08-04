package com.lrj.platform.interop.a2a;

import com.lrj.platform.interop.a2a.MessageSendParams.PushNotificationConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * A2A push 配置存储：{@code (tenantId, taskId) -> PushNotificationConfig}。**刻意跟 agent-service 的
 * 原生 webhook 分开**：interop 让 agent 任务的 webhook 回到 interop 自己的回调端点，再由 {@link A2aPushForwarder}
 * 按 A2A Task 信封格式回推客户端。登记过配置的 task 才回推，未登记的回调直接忽略。
 *
 * <p>本地默认内存；生产装配可替换持久 repository。无论后端如何，token 均以 AES-GCM 密文保存。
 */
public class A2aPushNotificationStore {

    private final A2aStateRepository repository;
    private final A2aPushTokenCipher cipher;
    private final Duration ttl;

    public A2aPushNotificationStore() {
        this(new InMemoryA2aStateRepository(), A2aPushTokenCipher.ephemeral(), Duration.ofDays(7));
    }

    public A2aPushNotificationStore(A2aPushTokenCipher cipher, Duration ttl) {
        this(new InMemoryA2aStateRepository(), cipher, ttl);
    }

    public A2aPushNotificationStore(A2aStateRepository repository,
                                    A2aPushTokenCipher cipher,
                                    Duration ttl) {
        this.repository = repository;
        this.cipher = cipher;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofDays(7) : ttl;
    }

    public void bindTask(String tenantId,
                         String userId,
                         String taskId,
                         String contextId,
                         String skill,
                         String messageId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        if (repository.get(tenantId, taskId).isPresent()) {
            return;
        }
        A2aTaskContextRecord created = new A2aTaskContextRecord(
                    "a2a-task-context.v1", 0, safe(tenantId), safe(userId), taskId,
                    nonBlank(contextId, taskId), nonBlank(skill, "unknown"),
                    nonBlank(messageId, taskId), null, null, null,
                    now, now, now.plus(ttl));
        repository.compareAndSet(created, null);
    }

    public void put(String tenantId, String taskId, PushNotificationConfig config) {
        if (taskId == null || config == null) {
            return;
        }
        bindTask(tenantId, "unknown", taskId, taskId, "unknown", taskId);
        mutate(tenantId, taskId, existing -> new A2aTaskContextRecord(
                        existing.schemaVersion(), existing.revision() + 1,
                        existing.tenantId(), existing.userId(), existing.taskId(),
                        existing.contextId(), existing.skill(), existing.messageId(),
                        config.url(), cipher.encrypt(config.token()), config.id(),
                        existing.createdAt(), Instant.now(), Instant.now().plus(ttl)));
    }

    public Optional<PushNotificationConfig> get(String tenantId, String taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return record(tenantId, taskId)
                .filter(record -> record.pushUrl() != null && !record.pushUrl().isBlank())
                .map(record -> new PushNotificationConfig(
                        record.pushUrl(), cipher.decrypt(record.pushTokenCiphertext()),
                        record.pushConfigId()));
    }

    public void remove(String tenantId, String taskId) {
        if (taskId != null) {
            mutate(tenantId, taskId, existing -> new A2aTaskContextRecord(
                            existing.schemaVersion(), existing.revision() + 1,
                            existing.tenantId(), existing.userId(), existing.taskId(),
                            existing.contextId(), existing.skill(), existing.messageId(),
                            null, null, null, existing.createdAt(), Instant.now(), existing.expiresAt()));
        }
    }

    public Optional<String> contextId(String tenantId, String taskId) {
        return record(tenantId, taskId).map(A2aTaskContextRecord::contextId);
    }

    public Optional<A2aTaskContextRecord> record(String tenantId, String taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        A2aTaskContextRecord record = repository.get(tenantId, taskId).orElse(null);
        if (record != null && record.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.ofNullable(record);
    }

    private void mutate(String tenantId,
                        String taskId,
                        java.util.function.Function<A2aTaskContextRecord,
                                A2aTaskContextRecord> operation) {
        for (int attempt = 0; attempt < 8; attempt++) {
            A2aTaskContextRecord current = repository.get(tenantId, taskId).orElse(null);
            if (current == null) {
                return;
            }
            A2aTaskContextRecord updated = operation.apply(current);
            if (repository.compareAndSet(updated, current.revision())) {
                return;
            }
        }
        throw new IllegalStateException("A2A context was modified concurrently");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
