package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Redis CAS adapter for multi-replica A2A context and encrypted push configuration state. */
public class RedisA2aStateRepository implements A2aStateRepository {

    private static final DefaultRedisScript<Long> CAS = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if ARGV[1] == '' then
              if current then return 0 end
            else
              if not current then return 0 end
              local decoded = cjson.decode(current)
              if tostring(decoded.revision) ~= ARGV[1] then return 0 end
            end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String namespace;

    public RedisA2aStateRepository(StringRedisTemplate redis,
                                   ObjectMapper json,
                                   String namespace) {
        this.redis = redis;
        this.json = json;
        this.namespace = namespace;
    }

    @Override
    public Optional<A2aTaskContextRecord> get(String tenantId, String taskId) {
        String value = redis.opsForValue().get(key(tenantId, taskId));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(value, A2aTaskContextRecord.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted A2A context record", exception);
        }
    }

    @Override
    public boolean compareAndSet(A2aTaskContextRecord record, Long expectedRevision) {
        long ttlMillis = Math.max(1, Duration.between(Instant.now(), record.expiresAt()).toMillis());
        try {
            Long updated = redis.execute(CAS, List.of(key(record.tenantId(), record.taskId())),
                    expectedRevision == null ? "" : expectedRevision.toString(),
                    json.writeValueAsString(record), Long.toString(ttlMillis));
            return Long.valueOf(1).equals(updated);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("A2A context serialization failed", exception);
        }
    }

    private String key(String tenantId, String taskId) {
        return namespace + ":" + (tenantId == null ? "" : tenantId) + ":" + taskId;
    }
}
