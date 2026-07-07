package com.lrj.platform.conversation.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内存的 {@link SemanticCacheStore}（默认，{@code app.conversation.semantic-cache.store=in-memory}）。
 *
 * <p>每租户一个有界桶：key=原始问题，value={@link SemanticCacheEntry}。按插入顺序 LRU 淘汰，
 * 单桶超过 {@code max-entries-per-tenant} 时丢最旧的一条。重启即丢 —— 单实例 / 本地开发 / 单测够用。
 * 多实例 / 需持久化时切 {@link RedisSemanticCacheStore}。
 */
@Component
@ConditionalOnProperty(name = "app.conversation.semantic-cache.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemorySemanticCacheStore implements SemanticCacheStore {

    private static final Logger log = LoggerFactory.getLogger(InMemorySemanticCacheStore.class);

    private final int maxEntriesPerTenant;
    private final ConcurrentMap<String, Map<String, SemanticCacheEntry>> buckets = new ConcurrentHashMap<>();

    public InMemorySemanticCacheStore(
            @Value("${app.conversation.semantic-cache.max-entries-per-tenant:1000}") int maxEntriesPerTenant) {
        this.maxEntriesPerTenant = Math.max(1, maxEntriesPerTenant);
        log.info("Semantic cache store: in-memory (maxEntriesPerTenant={})", this.maxEntriesPerTenant);
    }

    @Override
    public Optional<SemanticCacheHit> findNearest(String tenantId, float[] queryVector) {
        Map<String, SemanticCacheEntry> bucket = buckets.get(tenantId);
        if (bucket == null) {
            return Optional.empty();
        }
        SemanticCacheHit best = null;
        synchronized (bucket) {
            for (SemanticCacheEntry entry : bucket.values()) {
                double score = SemanticVectors.cosine(queryVector, entry.vector());
                if (best == null || score > best.score()) {
                    best = new SemanticCacheHit(entry.question(), entry.reply(), score);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public void put(String tenantId, SemanticCacheEntry entry) {
        Map<String, SemanticCacheEntry> bucket = buckets.computeIfAbsent(tenantId, k -> boundedBucket());
        synchronized (bucket) {
            bucket.put(entry.question(), entry);
        }
    }

    @Override
    public int invalidateTenant(String tenantId) {
        Map<String, SemanticCacheEntry> removed = buckets.remove(tenantId);
        return removed == null ? 0 : removed.size();
    }

    @Override
    public boolean invalidateQuestion(String tenantId, String question) {
        Map<String, SemanticCacheEntry> bucket = buckets.get(tenantId);
        if (bucket == null) {
            return false;
        }
        synchronized (bucket) {
            return bucket.remove(question) != null;
        }
    }

    private Map<String, SemanticCacheEntry> boundedBucket() {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SemanticCacheEntry> eldest) {
                return size() > maxEntriesPerTenant;
            }
        });
    }
}
