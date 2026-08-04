package com.lrj.platform.interop;

import com.lrj.platform.interop.a2a.InMemoryA2aStateRepository;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InteropStateConfigTest {

    private final InteropConfig config = new InteropConfig();

    @Test
    void redisStateRequiresDedicatedValidAes256Key() {
        InteropProperties properties = new InteropProperties();
        properties.setStateStore("redis");

        assertThatThrownBy(() -> config.a2aPushNotificationStore(
                new InMemoryA2aStateRepository(), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTEROP_A2A_PUSH_ENCRYPTION_KEY");

        properties.getA2a().setPushEncryptionKey("not-base64");
        assertThatThrownBy(() -> config.a2aPushNotificationStore(
                new InMemoryA2aStateRepository(), properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void encryptionKeyCannotReusePushHmacSecret() {
        InteropProperties properties = new InteropProperties();
        properties.setStateStore("redis");
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        properties.getA2a().setPushEncryptionKey(key);
        properties.getA2a().setPushHmacSecret(key);

        assertThatThrownBy(() -> config.a2aPushNotificationStore(
                new InMemoryA2aStateRepository(), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not reuse");
    }

    @Test
    void acceptsDedicatedAes256Key() {
        InteropProperties properties = new InteropProperties();
        properties.setStateStore("redis");
        properties.getA2a().setPushEncryptionKey(
                Base64.getEncoder().encodeToString(new byte[32]));

        assertThat(config.a2aPushNotificationStore(
                new InMemoryA2aStateRepository(), properties)).isNotNull();
    }
}
