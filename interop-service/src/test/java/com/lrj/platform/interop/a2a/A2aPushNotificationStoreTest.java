package com.lrj.platform.interop.a2a;

import com.lrj.platform.interop.a2a.MessageSendParams.PushNotificationConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
