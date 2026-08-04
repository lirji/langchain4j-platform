package com.lrj.platform.interop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.interop.AgentCapabilityRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

/** Redis-backed last-known-good registry shared by all interop replicas. */
public class RedisCapabilityRegistryStore implements CapabilityRegistryStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String key;

    public RedisCapabilityRegistryStore(StringRedisTemplate redis, ObjectMapper json, String key) {
        this.redis = redis;
        this.json = json;
        this.key = key;
    }

    @Override
    public Optional<AgentCapabilityRegistry> load() {
        String value = redis.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(value, AgentCapabilityRegistry.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted capability registry", exception);
        }
    }

    @Override
    public void save(AgentCapabilityRegistry registry) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(registry));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("capability registry serialization failed", exception);
        }
    }
}
