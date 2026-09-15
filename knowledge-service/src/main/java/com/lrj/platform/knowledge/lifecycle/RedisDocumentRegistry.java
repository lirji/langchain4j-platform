package com.lrj.platform.knowledge.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 持久化的 {@link DocumentRegistry}（{@code app.rag.registry.store=redis}）。
 *
 * <p>数据模型：每租户一个 Redis Hash {@code rag:docs:<tenantId>}，field=docId，value=DocumentInfo 的 JSON。
 * 天然 per-tenant 隔离（key 里带 tenantId），列表 = {@code HVALS}，详情 = {@code HGET}，删除 = {@code HDEL}。
 *
 * <p>跟持久化向量库（Milvus / PGVector）配套：重启 / 多实例后 {@code GET /rag/documents} 仍列得出
 * —— 解决"向量还在但列表空"的割裂。{@link RedisChatMemoryStore} 同款 {@link StringRedisTemplate} 思路。
 */
@Component
@ConditionalOnProperty(name = "app.rag.registry.store", havingValue = "redis")
public class RedisDocumentRegistry implements DocumentRegistry {

    private static final Logger log = LoggerFactory.getLogger(RedisDocumentRegistry.class);
    private static final String KEY_PREFIX = "rag:docs:";
    // 默认空前缀保持旧部署兼容；共享 Redis 使用项目前缀隔离持久业务键。
    @org.springframework.beans.factory.annotation.Value("${platform.redis.key-prefix:}")
    private String projectPrefix = "";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisDocumentRegistry(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
        log.info("DocumentRegistry: redis (key prefix={})", KEY_PREFIX);
    }

    @Override
    public void put(DocumentInfo info) {
        redis.opsForHash().put(key(info.tenantId()), info.docId(), toJson(info));
    }

    /** Redis Lua 在同一原子操作中读取版本并提交；重复版本保留首次提交的元数据。 */
    @Override
    public boolean commitVersion(DocumentInfo info) {
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>("""
                local raw = redis.call('HGET', KEYS[1], ARGV[1])
                local version = 0
                if raw then version = tonumber(cjson.decode(raw).version) end
                local incoming = tonumber(ARGV[2])
                if version == incoming then return 1 end
                if version + 1 ~= incoming then return 0 end
                redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
                return 1
                """, Long.class);
        return Long.valueOf(1).equals(redis.execute(script, List.of(key(info.tenantId())),
                info.docId(), Integer.toString(info.version()), toJson(info)));
    }

    @Override
    public Optional<DocumentInfo> get(String tenantId, String docId) {
        Object json = redis.opsForHash().get(key(tenantId), docId);
        return json == null ? Optional.empty() : Optional.of(fromJson(json.toString()));
    }

    @Override
    public List<DocumentInfo> list(String tenantId) {
        List<Object> values = redis.opsForHash().values(key(tenantId));
        List<DocumentInfo> out = new ArrayList<>(values.size());
        for (Object v : values) {
            out.add(fromJson(v.toString()));
        }
        return out;
    }

    @Override
    public Optional<DocumentInfo> remove(String tenantId, String docId) {
        Optional<DocumentInfo> existing = get(tenantId, docId);
        if (existing.isPresent()) {
            redis.opsForHash().delete(key(tenantId), docId);
        }
        return existing;
    }

    /** debug-only：SCAN 出所有 {@code rag:docs:*} key。生产别频繁调（keyspace 扫描）。 */
    @Override
    public Map<String, Collection<DocumentInfo>> snapshotAll() {
        Map<String, Collection<DocumentInfo>> out = new LinkedHashMap<>();
        Set<String> keys = redis.keys(projectPrefix + KEY_PREFIX + "*");
        if (keys == null) return out;
        for (String k : keys) {
            out.put(k.substring(projectPrefix.length() + KEY_PREFIX.length()), list(k.substring(projectPrefix.length() + KEY_PREFIX.length())));
        }
        return out;
    }

    private String key(String tenantId) {
        return projectPrefix + KEY_PREFIX + tenantId;
    }

    private String toJson(DocumentInfo info) {
        try {
            return mapper.writeValueAsString(info);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize DocumentInfo " + info.docId(), e);
        }
    }

    private DocumentInfo fromJson(String json) {
        try {
            return mapper.readValue(json, DocumentInfo.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize DocumentInfo: " + json, e);
        }
    }
}
