package com.lrj.platform.conversation.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 默认的确定性 hash embedder（{@code app.conversation.semantic-cache.embedding.provider=hash}）。
 *
 * <p>与 knowledge-service 的 {@code HashEmbeddingModel} 同款思路：把 token sha256 散列到定长桶、
 * 累加 ±1 后归一化。纯本地计算、无外部依赖、结果确定，因此 dev/test 零依赖即可跑语义缓存旁路逻辑。
 */
@Component
@ConditionalOnProperty(name = "app.conversation.semantic-cache.embedding.provider",
        havingValue = "hash", matchIfMissing = true)
public class HashSemanticCacheEmbedder implements SemanticCacheEmbedder {

    private static final Logger log = LoggerFactory.getLogger(HashSemanticCacheEmbedder.class);
    static final int DIMENSION = 64;

    public HashSemanticCacheEmbedder() {
        log.info("Semantic cache embedder: hash (dimension={})", DIMENSION);
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[DIMENSION];
        if (text == null || text.isBlank()) {
            return v;
        }
        String[] tokens = text.toLowerCase().split("\\s+");
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            byte[] hash = sha256(token);
            int idx = Byte.toUnsignedInt(hash[0]) % DIMENSION;
            v[idx] += (hash[1] & 1) == 0 ? 1.0f : -1.0f;
        }
        normalize(v);
        return v;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void normalize(float[] v) {
        double sum = 0.0;
        for (float x : v) {
            sum += x * x;
        }
        if (sum == 0.0) {
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < v.length; i++) {
            v[i] = v[i] / norm;
        }
    }
}
