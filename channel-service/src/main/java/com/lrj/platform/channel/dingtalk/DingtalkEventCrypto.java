package com.lrj.platform.channel.dingtalk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 钉钉机器人消息回调的签名校验（与钉钉开放平台约定一致）。
 *
 * <p>钉钉在回调请求头带 {@code timestamp} 与 {@code sign}，校验方式：
 * <pre>{@code sign == Base64( HmacSHA256( key = AppSecret, data = timestamp + "\n" + AppSecret ) )}</pre>
 * 与飞书的 {@code hex(SHA256(timestamp+nonce+encryptKey+body))} 不同——这是钉钉桥相对飞书桥的核心差异。
 *
 * <p>纯逻辑、可单测（给定 app-secret 与已知 timestamp 即可验证），不依赖钉钉在线凭据。
 * 这里用的是「机器人消息接收回调」的简单签名，不涉及事件订阅回调那套 aes_key/encrypt 握手。
 */
public class DingtalkEventCrypto {

    private final String appSecret;

    public DingtalkEventCrypto(String appSecret) {
        this.appSecret = appSecret == null ? "" : appSecret;
    }

    public boolean hasSecret() {
        return !appSecret.isEmpty();
    }

    /** 按 timestamp 计算期望签名（Base64）。 */
    public String expectedSign(String timestamp) {
        String stringToSign = nz(timestamp) + "\n" + appSecret;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signData);
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute dingtalk sign: " + e.getMessage(), e);
        }
    }

    /** 校验回调头的 timestamp/sign。 */
    public boolean signatureValid(String timestamp, String sign) {
        if (timestamp == null || timestamp.isBlank() || sign == null || sign.isBlank()) {
            return false;
        }
        String expected = expectedSign(timestamp);
        // 常量时间比较，避免计时侧信道
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sign.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
