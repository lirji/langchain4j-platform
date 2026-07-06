package com.lrj.platform.knowledge.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryGraphStore implements GraphStore {

    private final List<Triple> all = new CopyOnWriteArrayList<>();
    private final Map<String, List<Triple>> byEntity = new ConcurrentHashMap<>();

    @Override
    public void add(List<Triple> triples) {
        if (triples == null || triples.isEmpty()) {
            return;
        }
        for (Triple triple : triples) {
            all.add(triple);
            byEntity.computeIfAbsent(key(triple.tenantId(), triple.subject()), ignored -> new CopyOnWriteArrayList<>())
                    .add(triple);
            byEntity.computeIfAbsent(key(triple.tenantId(), triple.object()), ignored -> new CopyOnWriteArrayList<>())
                    .add(triple);
        }
    }

    @Override
    public List<Triple> neighbors(Set<String> seedSurfaces, int maxHops, String tenantId, String category) {
        if (seedSurfaces == null || seedSurfaces.isEmpty() || tenantId == null || tenantId.isBlank()) {
            return List.of();
        }
        int hops = Math.max(1, maxHops);
        Set<String> visitedEntities = new HashSet<>();
        Set<Triple> visitedTriples = new LinkedHashSet<>();
        Queue<Node> queue = new ArrayDeque<>();
        for (String seed : seedSurfaces) {
            if (seed == null || seed.isBlank()) {
                continue;
            }
            String normalized = normalize(seed);
            if (visitedEntities.add(normalized)) {
                queue.add(new Node(seed.trim(), 0));
            }
        }
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            if (node.depth() >= hops) {
                continue;
            }
            for (Triple triple : byEntity.getOrDefault(key(tenantId, node.surface()), List.of())) {
                if (!matches(triple, tenantId, category)) {
                    continue;
                }
                if (visitedTriples.add(triple)) {
                    enqueue(triple.subject(), node.depth() + 1, visitedEntities, queue);
                    enqueue(triple.object(), node.depth() + 1, visitedEntities, queue);
                }
            }
        }
        return List.copyOf(visitedTriples);
    }

    @Override
    public Set<String> entities(String tenantId, String category) {
        if (tenantId == null || tenantId.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Triple triple : all) {
            if (matches(triple, tenantId, category)) {
                result.add(triple.subject());
                result.add(triple.object());
            }
        }
        return result;
    }

    @Override
    public int removeBySourcePrefix(String tenantId, String sourceIdPrefix) {
        if (tenantId == null || tenantId.isBlank() || sourceIdPrefix == null || sourceIdPrefix.isBlank()) {
            return 0;
        }
        List<Triple> removed = new ArrayList<>();
        all.removeIf(triple -> {
            boolean matched = Objects.equals(tenantId, triple.tenantId())
                    && triple.sourceId() != null
                    && triple.sourceId().startsWith(sourceIdPrefix);
            if (matched) {
                removed.add(triple);
            }
            return matched;
        });
        if (!removed.isEmpty()) {
            rebuildIndex();
        }
        return removed.size();
    }

    @Override
    public int size() {
        return all.size();
    }

    private void rebuildIndex() {
        byEntity.clear();
        for (Triple triple : all) {
            byEntity.computeIfAbsent(key(triple.tenantId(), triple.subject()), ignored -> new CopyOnWriteArrayList<>())
                    .add(triple);
            byEntity.computeIfAbsent(key(triple.tenantId(), triple.object()), ignored -> new CopyOnWriteArrayList<>())
                    .add(triple);
        }
    }

    private static void enqueue(String surface, int depth, Set<String> visitedEntities, Queue<Node> queue) {
        String normalized = normalize(surface);
        if (visitedEntities.add(normalized)) {
            queue.add(new Node(surface, depth));
        }
    }

    private static boolean matches(Triple triple, String tenantId, String category) {
        if (!Objects.equals(tenantId, triple.tenantId())) {
            return false;
        }
        return category == null || category.isBlank() || Objects.equals(category, triple.category());
    }

    private static String key(String tenantId, String entity) {
        return tenantId + "\u0000" + normalize(entity);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record Node(String surface, int depth) {}
}
