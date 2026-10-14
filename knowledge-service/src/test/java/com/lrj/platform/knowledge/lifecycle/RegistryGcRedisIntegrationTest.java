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
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 使用共享Redis的随机命名空间验证真实SCAN及Lua；清理仅限本测试创建的键。 */
@EnabledIfEnvironmentVariable(named = "GC_REDIS_TEST", matches = "true")
class RegistryGcRedisIntegrationTest {
    @Test void scanningAndCheckpointsSurviveRegistryRestartAndRejectStaleWriters() {
        var config = new RedisStandaloneConfiguration("127.0.0.1", 46379);
        config.setPassword(System.getenv("GC_REDIS_PASSWORD"));
        var factory = new LettuceConnectionFactory(config); factory.afterPropertiesSet(); factory.start();
        var redis = new StringRedisTemplate(factory);
        String prefix = "gc-it-" + UUID.randomUUID() + ":";
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        try {
            var registry = new RedisDocumentRegistry(redis, mapper);
            ReflectionTestUtils.setField(registry, "projectPrefix", prefix);
            for (int i = 0; i < 25; i++) registry.put(new DocumentInfo("doc-" + i, "tenant-" + i % 3,
                    "test", "text/plain", 1, 1, 5, Instant.parse("2026-01-01T00:00:00Z"), null));
            var scan = registry.maintenanceScan(2); var found = new HashMap<String, DocumentInfo>();
            int pages = 0;
            while (!scan.exhausted() && pages++ < 1000) scan.nextPage().forEach(info -> found.put(info.docId(), info));
            assertThat(scan.exhausted()).isTrue(); assertThat(found).hasSize(25);
            var first = found.get("doc-0");
            assertThat(registry.markCleaned(first, 0, 1)).isTrue();
            assertThat(registry.markCleaned(first, 0, 1)).isFalse();
            var restarted = new RedisDocumentRegistry(redis, mapper);
            ReflectionTestUtils.setField(restarted, "projectPrefix", prefix);
            assertThat(restarted.cleanedThrough(first.tenantId(), first.docId())).isEqualTo(1);
            registry.remove(first.tenantId(), first.docId());
            assertThat(registry.markCleaned(first, 1, 2)).isFalse();
            registry.put(new DocumentInfo(first.docId(), first.tenantId(), "new", "text/plain", 1, 1, 1, Instant.now(), null));
            assertThat(registry.cleanedThrough(first.tenantId(), first.docId())).isZero();
            assertThat(registry.markCleaned(first, 0, 1)).isFalse();
        } finally {
            // 随机测试前缀，不接受外部清理范围；不运行全库清空。
            Set<String> keys = redis.keys(prefix + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
            factory.destroy();
        }
    }
}
