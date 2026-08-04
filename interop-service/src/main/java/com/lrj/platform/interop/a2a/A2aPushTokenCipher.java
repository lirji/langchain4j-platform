package com.lrj.platform.interop.a2a;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM envelope for A2A notification tokens stored outside the process. */
public final class A2aPushTokenCipher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKey key;

    public A2aPushTokenCipher(String base64Key) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key == null ? "" : base64Key);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("A2A push encryption key must be base64", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException("A2A push encryption key must contain exactly 32 bytes");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public static A2aPushTokenCipher ephemeral() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            return new A2aPushTokenCipher(Base64.getEncoder().encodeToString(
                    generator.generateKey().getEncoded()));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-256 is unavailable", exception);
        }
    }

    public String encrypt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
                    + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("A2A push token encryption failed", exception);
        }
    }

    public String decrypt(String envelope) {
        if (envelope == null || envelope.isBlank()) {
            return null;
        }
        String[] parts = envelope.split("\\.", -1);
        if (parts.length != 3 || !"v1".equals(parts[0])) {
            throw new IllegalArgumentException("invalid A2A push token envelope");
        }
        try {
            byte[] nonce = Base64.getUrlDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getUrlDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid A2A push token envelope", exception);
        }
    }
}
