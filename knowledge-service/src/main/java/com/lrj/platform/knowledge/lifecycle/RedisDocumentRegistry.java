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
            // 删除文档和其检查点必须原子完成，旧GC不能在重建文档之后写回旧进度。
            redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                    "redis.call('HDEL', KEYS[1], ARGV[1]); redis.call('HDEL', KEYS[2], ARGV[1]); return 1", Long.class),
                    List.of(key(tenantId), gcKey(tenantId)), docId);
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

    /** SCAN/HSCAN每次只发一个扫描命令；COUNT是Redis提示，返回页保留到逐条消费完毕。 */
    @Override
    public MaintenanceScan maintenanceScan(int pageSize) {
        if (pageSize < 1) throw new IllegalArgumentException("positive page size required");
        return new MaintenanceScan() {
            private String keyCursor = "0", hashCursor = "0", currentKey;
            private boolean started;
            private final java.util.ArrayDeque<String> keys = new java.util.ArrayDeque<>();
            public boolean exhausted() { return started && "0".equals(keyCursor) && keys.isEmpty() && currentKey == null; }
            public List<DocumentInfo> nextPage() {
                if (exhausted()) return List.of();
                if (currentKey == null && keys.isEmpty()) {
                    var result = scan("SCAN", keyCursor, "MATCH", projectPrefix + KEY_PREFIX + "*", "COUNT", Integer.toString(pageSize));
                    keyCursor = string(result.get(0)); started = true;
                    for (Object key : (List<?>) result.get(1)) keys.add(string(key));
                    return List.of();
                }
                if (currentKey == null) { currentKey = keys.removeFirst(); hashCursor = "0"; }
                var result = scan("HSCAN", currentKey, hashCursor, "COUNT", Integer.toString(pageSize));
                var fields = (List<?>) result.get(1);
                var page = new ArrayList<DocumentInfo>();
                for (int i = 1; i < fields.size(); i += 2) page.add(fromJson(string(fields.get(i))));
                hashCursor = string(result.get(0));
                if ("0".equals(hashCursor)) currentKey = null;
                return List.copyOf(page);
            }
        };
    }

    @Override
    public long cleanedThrough(String tenant, String doc) {
        Object value = redis.opsForHash().get(gcKey(tenant), doc);
        return value == null ? 0 : Long.parseLong(value.toString());
    }

    @Override
    public boolean markCleaned(DocumentInfo observed, long previous, long next) {
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>("""
                local raw = redis.call('HGET', KEYS[1], ARGV[1])
                if not raw or raw ~= ARGV[2] then return 0 end
                local old = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
                if old ~= tonumber(ARGV[3]) or tonumber(ARGV[4]) ~= old + 1 then return 0 end
                redis.call('HSET', KEYS[2], ARGV[1], ARGV[4]); return 1
                """, Long.class);
        return Long.valueOf(1).equals(redis.execute(script, List.of(key(observed.tenantId()), gcKey(observed.tenantId())),
                observed.docId(), toJson(observed), Long.toString(previous), Long.toString(next)));
    }

    private String gcKey(String tenant) { return projectPrefix + "rag:gc:" + tenant; }
    private List<?> scan(String command, String... args) {
        // 显式MULTI结果类型保留SCAN的嵌套数组；低层execute默认字节响应会丢失游标结构。
        if ("HSCAN".equals(command)) {
            return redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<List>(
                    "return redis.call('HSCAN', KEYS[1], ARGV[1], 'COUNT', ARGV[2])", List.class),
                    List.of(args[0]), args[1], args[3]);
        }
        return redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<List>(
                "return redis.call('SCAN', ARGV[1], 'MATCH', ARGV[2], 'COUNT', ARGV[3])", List.class),
                List.of(), args[0], args[2], args[4]);
    }

    private static String string(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : value.toString();
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
