package com.lrj.platform.conversation.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
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
 * <p>容量：与 {@link InMemorySemanticCacheStore} 同一 {@code max-entries-per-tenant} FIFO 语义。插入顺序记在同租户
 * 的 ZSET {@code conv:semcache:<tenantId>:order}（score = 首次写入毫秒；同问覆盖不改变位置），写入、登记顺序、
 * 淘汰最旧在一段 Lua 里原子完成。此前 Redis 实现不读该配置，桶随不同问题数无界增长，而每次查询都要 HVALS 整桶。
 * 升级前写入、没有顺序记录的旧条目在首次写入时按「最旧」补登记，因此会最先被淘汰。
 *
 * <p>可选 {@code ttl} 给整桶（两把 key 一起）设过期，避免长期不失效的陈旧回复堆积。
 */
@Component
@ConditionalOnProperty(name = "app.conversation.semantic-cache.store", havingValue = "redis")
public class RedisSemanticCacheStore implements SemanticCacheStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSemanticCacheStore.class);
    private static final String KEY_PREFIX = "conv:semcache:";
    private static final String ORDER_SUFFIX = ":order";

    /**
     * KEYS: hash, order；ARGV: field, json, nowMillis, maxEntries, ttlMillis(0=不过期)。返回被淘汰条数。
     */
    static final RedisScript<Long> PUT_AND_TRIM = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            redis.call('ZADD', KEYS[2], 'NX', ARGV[3], ARGV[1])
            if redis.call('ZCARD', KEYS[2]) < redis.call('HLEN', KEYS[1]) then
              for _, f in ipairs(redis.call('HKEYS', KEYS[1])) do
                redis.call('ZADD', KEYS[2], 'NX', 0, f)
              end
            end
            local excess = redis.call('ZCARD', KEYS[2]) - tonumber(ARGV[4])
            if excess > 0 then
              local oldest = redis.call('ZPOPMIN', KEYS[2], excess)
              for i = 1, #oldest, 2 do
                redis.call('HDEL', KEYS[1], oldest[i])
              end
            else
              excess = 0
            end
            local ttl = tonumber(ARGV[5])
            if ttl > 0 then
              redis.call('PEXPIRE', KEYS[1], ttl)
              redis.call('PEXPIRE', KEYS[2], ttl)
            end
            return excess
            """, Long.class);
    // 默认空前缀保持旧部署兼容；共享 Redis 使用项目前缀隔离持久业务键。
    @org.springframework.beans.factory.annotation.Value("${platform.redis.key-prefix:}")
    private String projectPrefix = "";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;
    private final int maxEntriesPerTenant;

    public RedisSemanticCacheStore(StringRedisTemplate redis,
                                   ObjectMapper mapper,
                                   @Value("${app.conversation.semantic-cache.redis.ttl:0s}") Duration ttl,
                                   @Value("${app.conversation.semantic-cache.max-entries-per-tenant:1000}") int maxEntriesPerTenant) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = ttl;
        this.maxEntriesPerTenant = Math.max(1, maxEntriesPerTenant);
        log.info("Semantic cache store: redis (key prefix={}, ttl={}, maxEntriesPerTenant={})", KEY_PREFIX,
                ttl == null || ttl.isZero() || ttl.isNegative() ? "none" : ttl, this.maxEntriesPerTenant);
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
        long ttlMillis = ttl == null || ttl.isNegative() ? 0L : ttl.toMillis();
        Long evicted = redis.execute(PUT_AND_TRIM, List.of(key, key + ORDER_SUFFIX),
                field(entry.question()), toJson(entry), String.valueOf(System.currentTimeMillis()),
                String.valueOf(maxEntriesPerTenant), String.valueOf(ttlMillis));
        if (evicted != null && evicted > 0 && log.isDebugEnabled()) {
            log.debug("semantic cache tenant={} evicted {} eldest entries (max={})", tenantId, evicted, maxEntriesPerTenant);
        }
    }

    @Override
    public int invalidateTenant(String tenantId) {
        String key = key(tenantId);
        Long size = redis.opsForHash().size(key);
        redis.delete(List.of(key, key + ORDER_SUFFIX));
        return size == null ? 0 : size.intValue();
    }

    @Override
    public boolean invalidateQuestion(String tenantId, String question) {
        String key = key(tenantId);
        String field = field(question);
        redis.opsForZSet().remove(key + ORDER_SUFFIX, field);
        Long removed = redis.opsForHash().delete(key, field);
        return removed != null && removed > 0;
    }

    private String key(String tenantId) {
        return projectPrefix + KEY_PREFIX + tenantId;
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
