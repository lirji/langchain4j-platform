package com.lrj.platform.knowledge.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 显式启用的共享 Redis 验证，仅写随机测试租户，检查 ACL 与真实 Lua 原子提交。 */
@EnabledIfEnvironmentVariable(named = "DEV_INFRA_SMOKE", matches = "true")
class DevInfraRedisIntegrationTest {
    @Test
    void projectAclAndVersionCommitWorkAgainstSharedRedis() {
        var config = new RedisStandaloneConfiguration("127.0.0.1", 46379);
        config.setUsername("lc4j");
        config.setPassword(System.getenv("LC4J_REDIS_PASSWORD"));
        var factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        var redis = new StringRedisTemplate(factory);
        String tenant = "infra-probe-" + UUID.randomUUID();
        String key = "lc4j:rag:docs:" + tenant;
        try {
            // Spring Redis 健康检查依赖 INFO；ACL 需要显式允许该只读诊断命令。
            try (var connection = factory.getConnection()) {
                assertThat(connection.serverCommands().info()).isNotEmpty();
            }
            var registry = new RedisDocumentRegistry(redis, new ObjectMapper().registerModule(new JavaTimeModule()));
            ReflectionTestUtils.setField(registry, "projectPrefix", "lc4j:");
            var first = new DocumentInfo("doc", tenant, "probe", "text/plain", 1, 1, 1, Instant.now(), null);
            var second = new DocumentInfo("doc", tenant, "probe", "text/plain", 1, 1, 2, Instant.now(), null);
            assertThat(registry.commitVersion(first)).isTrue();
            assertThat(registry.commitVersion(second)).isTrue();
            assertThat(registry.commitVersion(first)).isFalse();
            assertThat(registry.get(tenant, "doc")).contains(second);
            assertThat(redis.hasKey(key)).isTrue();
            assertThatThrownBy(() -> redis.opsForValue().get("outside-project:probe"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            redis.delete(key);
            factory.destroy();
        }
    }
}
