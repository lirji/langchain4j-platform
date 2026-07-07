package com.lrj.platform.channel.feishu;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 飞书事件加解密与签名校验（与飞书开放平台约定一致）。
 *
 * <ul>
 *   <li><b>解密</b>（配了 encrypt-key 时事件体为 {@code {"encrypt":"base64..."}}）：
 *       {@code AES-256-CBC}，key = {@code SHA256(encryptKey)}，base64 解码后前 16 字节为 IV、其余为密文，PKCS5/7 padding。</li>
 *   <li><b>签名</b>：{@code X-Lark-Signature = hex(SHA256(timestamp + nonce + encryptKey + rawBody))}。</li>
 * </ul>
 *
 * <p>纯逻辑、可单测（给定 encrypt-key 与已知向量即可验证），不依赖飞书凭据。
 */
public class FeishuEventCrypto {

    private final String encryptKey;
    private final byte[] aesKey; // SHA256(encryptKey)

    public FeishuEventCrypto(String encryptKey) {
        this.encryptKey = encryptKey == null ? "" : encryptKey;
        this.aesKey = sha256(this.encryptKey.getBytes(StandardCharsets.UTF_8));
    }

    public boolean hasEncryptKey() {
        return !encryptKey.isEmpty();
    }

    /** AES-256-CBC 解密飞书 {@code encrypt} 字段，返回明文 JSON。 */
    public String decrypt(String encryptBase64) {
        try {
            byte[] data = Base64.getDecoder().decode(encryptBase64);
            if (data.length <= 16) {
                throw new IllegalArgumentException("encrypt payload too short");
            }
            byte[] iv = Arrays.copyOfRange(data, 0, 16);
            byte[] cipherText = Arrays.copyOfRange(data, 16, data.length);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("failed to decrypt feishu event: " + e.getMessage(), e);
        }
    }

    /** 校验 X-Lark-Signature。 */
    public boolean signatureValid(String timestamp, String nonce, String rawBody, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        String content = nz(timestamp) + nz(nonce) + encryptKey + nz(rawBody);
        String expected = HexFormat.of().formatHex(sha256(content.getBytes(StandardCharsets.UTF_8)));
        // 常量时间比较
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
