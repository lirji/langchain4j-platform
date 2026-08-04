package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.interop.a2a.MessageSendParams.PushNotificationConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2aPushNotificationStoreTest：验证 {@link A2aPushNotificationStore} 的存/取/删往返、按租户隔离
 * （同 taskId 不同租户不串号），以及对 null taskId 或 config 的忽略。
 */
class A2aPushNotificationStoreTest {

    private final A2aPushNotificationStore store = new A2aPushNotificationStore();
    private final PushNotificationConfig cfg = new PushNotificationConfig("https://client/hook", "tok", "id");

    @Test
    void putGetRemoveRoundTrips() {
        store.put("acme", "t1", cfg);
        assertThat(store.get("acme", "t1")).contains(cfg);
        store.remove("acme", "t1");
        assertThat(store.get("acme", "t1")).isEmpty();
    }

    @Test
    void isolatesByTenant() {
        store.put("acme", "t1", cfg);
        assertThat(store.get("beta", "t1")).isEmpty(); // 同 taskId 不同租户不串号
        assertThat(store.get("acme", "t1")).contains(cfg);
    }

    @Test
    void ignoresNullTaskOrConfig() {
        store.put("acme", null, cfg);
        store.put("acme", "t2", null);
        assertThat(store.get("acme", "t2")).isEmpty();
    }

    @Test
    void persistsRealContextAndNeverStoresPushTokenInPlaintext() throws Exception {
        store.bindTask("acme", "alice", "t1", "conversation-7", "deep-research", "message-7");
        store.put("acme", "t1", cfg);

        A2aTaskContextRecord record = store.record("acme", "t1").orElseThrow();
        String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(record);

        assertThat(store.contextId("acme", "t1")).contains("conversation-7");
        assertThat(record.schemaVersion()).isEqualTo("a2a-task-context.v1");
        assertThat(record.pushTokenCiphertext()).isNotBlank();
        assertThat(serialized).doesNotContain("\"tok\"");
        assertThat(store.get("acme", "t1")).contains(cfg);
    }

    @Test
    void restoresContextAndEncryptedPushConfigurationAfterFacadeRestart() {
        InMemoryA2aStateRepository repository = new InMemoryA2aStateRepository();
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        A2aPushTokenCipher cipher = new A2aPushTokenCipher(key);
        A2aPushNotificationStore first = new A2aPushNotificationStore(
                repository, cipher, Duration.ofDays(1));
        first.bindTask("acme", "alice", "t7", "conversation-7", "deep-research", "m7");
        first.put("acme", "t7", cfg);

        A2aPushNotificationStore restarted = new A2aPushNotificationStore(
                repository, new A2aPushTokenCipher(key), Duration.ofDays(1));

        assertThat(restarted.contextId("acme", "t7")).contains("conversation-7");
        assertThat(restarted.get("acme", "t7")).contains(cfg);
    }
}
