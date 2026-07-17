package com.lrj.platform.channel.feishu;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FeishuEventCryptoTest：验证 {@link FeishuEventCrypto} 的加解密与验签——AES/CBC/PKCS5（key=sha256(encryptKey)、
 * IV 前置）解密可往返、签名为 hex(SHA-256(timestamp+nonce+encryptKey+body))，覆盖正确/错误/空签名与 {@code hasEncryptKey} 判定。
 */
class FeishuEventCryptoTest {

    private static final String KEY = "test-encrypt-key";

    @Test
    void decrypt_roundtripsAesCbc() throws Exception {
        String plaintext = "{\"type\":\"url_verification\",\"challenge\":\"c-123\"}";
        String encrypted = encrypt(plaintext, KEY);

        assertThat(new FeishuEventCrypto(KEY).decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void signatureValid_matchesSha256OfTimestampNonceKeyBody() throws Exception {
        FeishuEventCrypto crypto = new FeishuEventCrypto(KEY);
        String ts = "1700000000", nonce = "n-1", body = "{\"encrypt\":\"x\"}";
        String sig = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((ts + nonce + KEY + body).getBytes(StandardCharsets.UTF_8)));

        assertThat(crypto.signatureValid(ts, nonce, body, sig)).isTrue();
        assertThat(crypto.signatureValid(ts, nonce, body, "deadbeef")).isFalse();
        assertThat(crypto.signatureValid(ts, nonce, body, null)).isFalse();
    }

    @Test
    void hasEncryptKey_reflectsConfig() {
        assertThat(new FeishuEventCrypto(KEY).hasEncryptKey()).isTrue();
        assertThat(new FeishuEventCrypto("").hasEncryptKey()).isFalse();
    }

    /** 用与飞书一致的方案加密：key=sha256(encryptKey)，IV(16)+密文，AES/CBC/PKCS5，base64。 */
    private static String encrypt(String plaintext, String encryptKey) throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256").digest(encryptKey.getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[16]; // 固定 IV 便于确定性测试
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.getEncoder().encodeToString(out);
    }
}
