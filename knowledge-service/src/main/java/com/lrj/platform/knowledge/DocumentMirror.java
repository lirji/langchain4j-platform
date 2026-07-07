package com.lrj.platform.knowledge;

import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * In-process mirror of ingested segments, physically partitioned per tenant so the hybrid keyword
 * index shares the same collection-per-tenant strong isolation as the vector store: one tenant's
 * segments never live in another tenant's partition.
 */
@Component
public class DocumentMirror {

    private static final String UNKNOWN_TENANT = "__unknown__";

    private final ConcurrentMap<String, List<TextSegment>> byTenant = new ConcurrentHashMap<>();

    public void add(List<TextSegment> newSegments) {
        for (TextSegment segment : newSegments) {
            byTenant.computeIfAbsent(tenantOf(segment), k -> new CopyOnWriteArrayList<>()).add(segment);
        }
    }

    /** All segments across every tenant (aggregate view; prefer {@link #all(String)} for tenant-scoped reads). */
    public List<TextSegment> all() {
        List<TextSegment> aggregate = new ArrayList<>();
        byTenant.values().forEach(aggregate::addAll);
        return List.copyOf(aggregate);
    }

    /** Segments belonging to a single tenant partition only. */
    public List<TextSegment> all(String tenantId) {
        List<TextSegment> list = byTenant.get(tenantId == null || tenantId.isBlank() ? UNKNOWN_TENANT : tenantId);
        return list == null ? List.of() : List.copyOf(list);
    }

    public int size() {
        return byTenant.values().stream().mapToInt(List::size).sum();
    }

    public int removeWhere(Predicate<TextSegment> predicate) {
        int before = size();
        byTenant.values().forEach(list -> list.removeIf(predicate));
        byTenant.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return before - size();
    }

    private static String tenantOf(TextSegment segment) {
        if (segment != null && segment.metadata() != null) {
            String tenantId = segment.metadata().getString("tenantId");
            if (tenantId != null && !tenantId.isBlank()) {
                return tenantId;
            }
        }
        return UNKNOWN_TENANT;
    }
}
