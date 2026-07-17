package com.lrj.platform.channel.dingtalk;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DingtalkEventCryptoTest：验证 {@link DingtalkEventCrypto} 的回调签名校验——签名为
 * Base64(HmacSHA256(appSecret, timestamp + "\n" + appSecret))，覆盖正确签名通过、错误/空签名与错误时间戳被拒、
 * 以及 {@code hasSecret} 对配置的反映。
 */
class DingtalkEventCryptoTest {

    private static final String SECRET = "test-app-secret";

    @Test
    void signatureValid_matchesBase64HmacOfTimestampAndSecret() throws Exception {
        DingtalkEventCrypto crypto = new DingtalkEventCrypto(SECRET);
        String ts = "1700000000000";
        String sign = expectedSign(ts, SECRET);

        assertThat(crypto.signatureValid(ts, sign)).isTrue();
        assertThat(crypto.signatureValid(ts, "not-the-sign")).isFalse();
        assertThat(crypto.signatureValid(ts, null)).isFalse();
        assertThat(crypto.signatureValid(null, sign)).isFalse();
    }

    @Test
    void signatureValid_wrongTimestamp_fails() throws Exception {
        DingtalkEventCrypto crypto = new DingtalkEventCrypto(SECRET);
        String sign = expectedSign("1700000000000", SECRET);

        assertThat(crypto.signatureValid("1700000009999", sign)).isFalse();
    }

    @Test
    void hasSecret_reflectsConfig() {
        assertThat(new DingtalkEventCrypto(SECRET).hasSecret()).isTrue();
        assertThat(new DingtalkEventCrypto("").hasSecret()).isFalse();
        assertThat(new DingtalkEventCrypto(null).hasSecret()).isFalse();
    }

    /** 与钉钉一致：Base64(HmacSHA256(appSecret, timestamp + "\n" + appSecret))。 */
    private static String expectedSign(String timestamp, String secret) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
    }
}
