package com.lrj.platform.knowledge.lifecycle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内存的 {@link DocumentRegistry}（默认）。重启即丢 —— 单实例 / 本地开发够用。
 * 多实例 / 重启需持久化时切 {@link RedisDocumentRegistry}（{@code app.rag.registry.store=redis}）。
 */
@Component
@ConditionalOnProperty(name = "app.rag.registry.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryDocumentRegistry implements DocumentRegistry {

    private final ConcurrentMap<String, ConcurrentMap<String, DocumentInfo>> map = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, ConcurrentMap<String, Long>> cleaned = new ConcurrentHashMap<>();

    @Override
    public void put(DocumentInfo info) {
        map.computeIfAbsent(info.tenantId(), k -> new ConcurrentHashMap<>())
                .put(info.docId(), info);
    }

    /** 在同一文档的 compute 内校验并提交，防止旧 worker 覆盖较新版本。 */
    @Override
    public boolean commitVersion(DocumentInfo info) {
        var accepted = new java.util.concurrent.atomic.AtomicBoolean();
        map.computeIfAbsent(info.tenantId(), key -> new ConcurrentHashMap<>()).compute(info.docId(), (key, current) -> {
            int version = current == null ? 0 : current.version();
            if (version == info.version()) { accepted.set(true); return current; }
            if (version + 1 == info.version()) { accepted.set(true); return info; }
            return current;
        });
        return accepted.get();
    }

    @Override
    public Optional<DocumentInfo> get(String tenantId, String docId) {
        ConcurrentMap<String, DocumentInfo> tenantMap = map.get(tenantId);
        if (tenantMap == null) return Optional.empty();
        return Optional.ofNullable(tenantMap.get(docId));
    }

    @Override
    public List<DocumentInfo> list(String tenantId) {
        ConcurrentMap<String, DocumentInfo> tenantMap = map.get(tenantId);
        if (tenantMap == null) return List.of();
        return List.copyOf(tenantMap.values());
    }

    @Override
    public Optional<DocumentInfo> remove(String tenantId, String docId) {
        ConcurrentMap<String, DocumentInfo> tenantMap = map.get(tenantId);
        if (tenantMap == null) return Optional.empty();
        var removed = new java.util.concurrent.atomic.AtomicReference<DocumentInfo>();
        tenantMap.computeIfPresent(docId, (id, value) -> {
            cleaned.computeIfAbsent(tenantId, ignored -> new ConcurrentHashMap<>()).remove(docId);
            removed.set(value); return null;
        });
        return Optional.ofNullable(removed.get());
    }

    /** 弱一致迭代不会复制全量文档，新增项最迟在下一轮扫描处理。 */
    @Override
    public MaintenanceScan maintenanceScan(int pageSize) {
        if (pageSize < 1) throw new IllegalArgumentException("positive page size required");
        var iterator = map.values().stream().flatMap(docs -> docs.values().stream()).iterator();
        return new MaintenanceScan() {
            public List<DocumentInfo> nextPage() {
                var page = new java.util.ArrayList<DocumentInfo>();
                while (page.size() < pageSize && iterator.hasNext()) page.add(iterator.next());
                return List.copyOf(page);
            }
            public boolean exhausted() { return !iterator.hasNext(); }
        };
    }

    @Override
    public long cleanedThrough(String tenant, String doc) {
        return cleaned.getOrDefault(tenant, new ConcurrentHashMap<>()).getOrDefault(doc, 0L);
    }

    @Override
    public boolean markCleaned(DocumentInfo observed, long previous, long next) {
        var accepted = new java.util.concurrent.atomic.AtomicBoolean();
        map.computeIfAbsent(observed.tenantId(), ignored -> new ConcurrentHashMap<>()).computeIfPresent(observed.docId(), (id, current) -> {
            var progress = cleaned.computeIfAbsent(observed.tenantId(), ignored -> new ConcurrentHashMap<>());
            if (current.equals(observed) && progress.getOrDefault(id, 0L) == previous && next == previous + 1) {
                progress.put(id, next); accepted.set(true);
            }
            return current;
        });
        return accepted.get();
    }

    @Override
    public Map<String, Collection<DocumentInfo>> snapshotAll() {
        Map<String, Collection<DocumentInfo>> out = new LinkedHashMap<>();
        map.forEach((tenant, docs) -> out.put(tenant, List.copyOf(docs.values())));
        return out;
    }
}
