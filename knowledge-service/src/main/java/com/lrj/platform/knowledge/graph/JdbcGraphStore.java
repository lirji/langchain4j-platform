package com.lrj.platform.knowledge.graph;

import com.lrj.platform.knowledge.graph.mapper.GraphStoreMapper;
import com.lrj.platform.knowledge.graph.mapper.GraphStoreRow;
import com.lrj.platform.persistence.PlusMapperFactory;
import com.lrj.platform.persistence.Rows;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * {@link GraphStore} 的 JDBC 实现（{@code app.rag.graph.store=jdbc} 时启用）：三元组持久化到
 * {@code RAG_GRAPH_TRIPLE} 表，schema 由独立版本化 migration 管理，启动只验证 contract。
 * 以 SHA-256 派生 GRAPH_ID 做幂等 upsert，{@link #neighbors} 在库上按
 * 归一化实体 key 逐跳查询做 BFS 邻域遍历，均按 tenantId（及可选 category）隔离。
 */
public class JdbcGraphStore implements GraphStore {

    private final GraphStoreMapper db;

    public JdbcGraphStore(DataSource dataSource) {
        this.db = PlusMapperFactory.create(dataSource, GraphStoreMapper.class);
        init();
    }

    private void init() {
        db.readInit1();
    }

    @Override
    public void add(List<Triple> triples) {
        if (triples == null || triples.isEmpty()) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        for (Triple triple : triples) {
            String graphId = graphId(triple);
            db.writeAdd2(
                    graphId,
                    triple.tenantId(),
                    triple.category(),
                    triple.subject(),
                    normalize(triple.subject()),
                    triple.relation(),
                    triple.object(),
                    normalize(triple.object()),
                    triple.sourceId(),
                    now);
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
                queue.add(new Node(normalized, 0));
            }
        }
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            if (node.depth() >= hops) {
                continue;
            }
            for (Triple triple : triplesForEntity(tenantId, node.entityKey(), category)) {
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
        List<Triple> triples;
        if (category == null || category.isBlank()) {
            triples = Rows.map(db.readEntities1(tenantId), this::mapTriple);
        } else {
            triples = Rows.map(db.readEntities2(tenantId, category), this::mapTriple);
        }
        Set<String> result = new LinkedHashSet<>();
        for (Triple triple : triples) {
            result.add(triple.subject());
            result.add(triple.object());
        }
        return result;
    }

    @Override
    public int removeBySourcePrefix(String tenantId, String sourceIdPrefix) {
        if (tenantId == null || tenantId.isBlank() || sourceIdPrefix == null || sourceIdPrefix.isBlank()) {
            return 0;
        }
        return db.writeRemoveBySourcePrefix1(tenantId, escapeLike(sourceIdPrefix) + "%");
    }

    @Override
    public int size() {
        Integer count = Rows.one(db.readSize1());
        return count == null ? 0 : count;
    }

    private List<Triple> triplesForEntity(String tenantId, String entityKey, String category) {
        if (category == null || category.isBlank()) {
            return Rows.map(db.readTriplesForEntity1(tenantId, entityKey, entityKey), this::mapTriple);
        }
        return Rows.map(db.readTriplesForEntity2(tenantId, category, entityKey, entityKey), this::mapTriple);
    }

    private Triple mapTriple(GraphStoreRow rs, int rowNum) {
        return new Triple(
                rs.subjectText,
                rs.relationText,
                rs.objectText,
                rs.sourceId,
                rs.tenantId,
                rs.category);
    }

    private static void enqueue(String surface, int depth, Set<String> visitedEntities, Queue<Node> queue) {
        String normalized = normalize(surface);
        if (visitedEntities.add(normalized)) {
            queue.add(new Node(normalized, depth));
        }
    }

    private static String graphId(Triple triple) {
        String raw = String.join("\u0000",
                triple.tenantId(),
                Objects.toString(triple.category(), ""),
                triple.sourceId(),
                triple.subject(),
                triple.relation(),
                triple.object());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record Node(String entityKey, int depth) {
    }
}
