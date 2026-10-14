package com.lrj.platform.conversation.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Redis 上验证 {@link RedisSemanticCacheStore} 的容量上限（Lua 语义无法用 mock 证明）。
 * 显式启用：{@code DEV_INFRA_SMOKE=true LC4J_REDIS_PASSWORD=...}，只写随机测试租户并在结束时删除。
 */
@EnabledIfEnvironmentVariable(named = "DEV_INFRA_SMOKE", matches = "true")
class RedisSemanticCacheStoreIntegrationTest {

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private final String tenant = "semcache-probe-" + UUID.randomUUID();

    @BeforeEach
    void connect() {
        var config = new RedisStandaloneConfiguration("127.0.0.1", 46379);
        config.setUsername("lc4j");
        config.setPassword(System.getenv("LC4J_REDIS_PASSWORD"));
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @AfterEach
    void cleanup() {
        redis.delete(java.util.List.of(hashKey(), hashKey() + ":order"));
        factory.destroy();
    }

    @Test
    void bucketIsCappedAndEvictsEldestFirst() {
        RedisSemanticCacheStore store = store(2, Duration.ZERO);
        store.put(tenant, entry("q1", 1f, 0f));
        store.put(tenant, entry("q2", 0f, 1f));
        store.put(tenant, entry("q3", -1f, 0f));

        assertThat(redis.opsForHash().size(hashKey())).isEqualTo(2);
        assertThat(redis.opsForZSet().size(hashKey() + ":order")).isEqualTo(2);
        assertThat(store.invalidateQuestion(tenant, "q1")).as("最旧的 q1 被淘汰").isFalse();
        assertThat(store.findNearest(tenant, new float[]{-1f, 0f}))
                .hasValueSatisfying(hit -> assertThat(hit.reply()).isEqualTo("r-q3"));
    }

    @Test
    void overwritingAQuestionKeepsItsPositionLikeTheInMemoryStore() {
        RedisSemanticCacheStore store = store(2, Duration.ZERO);
        store.put(tenant, entry("q1", 1f, 0f));
        store.put(tenant, entry("q2", 0f, 1f));
        store.put(tenant, new SemanticCacheEntry("q1", new float[]{1f, 0f}, "r-q1-new"));
        store.put(tenant, entry("q3", -1f, 0f));

        assertThat(store.invalidateQuestion(tenant, "q1")).as("覆盖不刷新位置，q1 仍最旧").isFalse();
        assertThat(store.invalidateQuestion(tenant, "q2")).isTrue();
    }

    @Test
    void legacyBucketWithoutOrderIsTrimmedOnFirstWrite() throws Exception {
        // 升级前的桶：只有 Hash、没有顺序 ZSET，且已超出上限。
        ObjectMapper mapper = new ObjectMapper();
        for (int i = 0; i < 5; i++) {
            redis.opsForHash().put(hashKey(), "legacy-" + i, mapper.writeValueAsString(entry("legacy" + i, 1f, 0f)));
        }
        RedisSemanticCacheStore store = store(3, Duration.ZERO);

        store.put(tenant, entry("fresh", 0f, 1f));

        assertThat(redis.opsForHash().size(hashKey())).isEqualTo(3);
        assertThat(redis.opsForHash().hasKey(hashKey(), "legacy-0")).as("旧条目按最旧补登记并先淘汰").isFalse();
        assertThat(store.invalidateQuestion(tenant, "fresh")).as("新条目不被旧数据挤掉").isTrue();
    }

    @Test
    void ttlAppliesToBothKeysAndInvalidateTenantClearsBoth() {
        RedisSemanticCacheStore store = store(10, Duration.ofMinutes(5));
        store.put(tenant, entry("q1", 1f, 0f));

        assertThat(redis.getExpire(hashKey())).isPositive();
        assertThat(redis.getExpire(hashKey() + ":order")).isPositive();
        assertThat(store.invalidateTenant(tenant)).isEqualTo(1);
        assertThat(redis.hasKey(hashKey())).isFalse();
        assertThat(redis.hasKey(hashKey() + ":order")).isFalse();
    }

    private RedisSemanticCacheStore store(int max, Duration ttl) {
        RedisSemanticCacheStore store = new RedisSemanticCacheStore(redis, new ObjectMapper(), ttl, max);
        ReflectionTestUtils.setField(store, "projectPrefix", "lc4j:");
        return store;
    }

    private String hashKey() {
        return "lc4j:conv:semcache:" + tenant;
    }

    private static SemanticCacheEntry entry(String question, float x, float y) {
        return new SemanticCacheEntry(question, new float[]{x, y}, "r-" + question);
    }
}
