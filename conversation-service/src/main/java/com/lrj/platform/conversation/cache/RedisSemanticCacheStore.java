package com.lrj.platform.conversation.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 持久化的 {@link SemanticCacheStore}（{@code app.conversation.semantic-cache.store=redis}）。
 *
 * <p>数据模型：每租户一个 Redis Hash {@code conv:semcache:<tenantId>}，field=sha256(原始问题)，
 * value={@link SemanticCacheEntry} 的 JSON。key 里带 tenantId → 天然 per-tenant 隔离。
 * 相似度检索用 {@code HVALS} 拉回本租户所有条目后在应用侧算余弦（无 RediSearch 依赖），
 * 与 {@code RedisDocumentRegistry} 同款 {@link StringRedisTemplate} 思路。
 *
 * <p>可选 {@code ttl} 给整桶设过期，避免长期不失效的陈旧回复堆积。
 */
@Component
@ConditionalOnProperty(name = "app.conversation.semantic-cache.store", havingValue = "redis")
public class RedisSemanticCacheStore implements SemanticCacheStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSemanticCacheStore.class);
    private static final String KEY_PREFIX = "conv:semcache:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public RedisSemanticCacheStore(StringRedisTemplate redis,
                                   ObjectMapper mapper,
                                   @Value("${app.conversation.semantic-cache.redis.ttl:0s}") Duration ttl) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = ttl;
        log.info("Semantic cache store: redis (key prefix={}, ttl={})", KEY_PREFIX,
                ttl == null || ttl.isZero() || ttl.isNegative() ? "none" : ttl);
    }

    @Override
    public Optional<SemanticCacheHit> findNearest(String tenantId, float[] queryVector) {
        List<Object> values = redis.opsForHash().values(key(tenantId));
        SemanticCacheHit best = null;
        for (Object v : values) {
            SemanticCacheEntry entry = fromJson(v.toString());
            double score = SemanticVectors.cosine(queryVector, entry.vector());
            if (best == null || score > best.score()) {
                best = new SemanticCacheHit(entry.question(), entry.reply(), score);
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public void put(String tenantId, SemanticCacheEntry entry) {
        String key = key(tenantId);
        redis.opsForHash().put(key, field(entry.question()), toJson(entry));
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            redis.expire(key, ttl);
        }
    }

    @Override
    public int invalidateTenant(String tenantId) {
        String key = key(tenantId);
        Long size = redis.opsForHash().size(key);
        redis.delete(key);
        return size == null ? 0 : size.intValue();
    }

    @Override
    public boolean invalidateQuestion(String tenantId, String question) {
        Long removed = redis.opsForHash().delete(key(tenantId), field(question));
        return removed != null && removed > 0;
    }

    private static String key(String tenantId) {
        return KEY_PREFIX + tenantId;
    }

    private static String field(String question) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(question.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String toJson(SemanticCacheEntry entry) {
        try {
            return mapper.writeValueAsString(entry);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize SemanticCacheEntry", e);
        }
    }

    private SemanticCacheEntry fromJson(String json) {
        try {
            return mapper.readValue(json, SemanticCacheEntry.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize SemanticCacheEntry: " + json, e);
        }
    }
}
