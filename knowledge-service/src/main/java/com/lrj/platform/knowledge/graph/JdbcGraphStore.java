package com.lrj.platform.knowledge.graph;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

public class JdbcGraphStore implements GraphStore {

    private final JdbcTemplate jdbc;

    public JdbcGraphStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        init();
    }

    private void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS RAG_GRAPH_TRIPLE (
                  GRAPH_ID VARCHAR(128) NOT NULL PRIMARY KEY,
                  TENANT_ID VARCHAR(128) NOT NULL,
                  CATEGORY VARCHAR(128),
                  SUBJECT_TEXT VARCHAR(512) NOT NULL,
                  SUBJECT_KEY VARCHAR(512) NOT NULL,
                  RELATION_TEXT VARCHAR(256) NOT NULL,
                  OBJECT_TEXT VARCHAR(512) NOT NULL,
                  OBJECT_KEY VARCHAR(512) NOT NULL,
                  SOURCE_ID VARCHAR(512),
                  CREATED_AT BIGINT NOT NULL
                )""");
        createIndex("IDX_RAG_GRAPH_SUBJECT", "CREATE INDEX IDX_RAG_GRAPH_SUBJECT ON RAG_GRAPH_TRIPLE (TENANT_ID, SUBJECT_KEY)");
        createIndex("IDX_RAG_GRAPH_OBJECT", "CREATE INDEX IDX_RAG_GRAPH_OBJECT ON RAG_GRAPH_TRIPLE (TENANT_ID, OBJECT_KEY)");
        createIndex("IDX_RAG_GRAPH_SOURCE", "CREATE INDEX IDX_RAG_GRAPH_SOURCE ON RAG_GRAPH_TRIPLE (TENANT_ID, SOURCE_ID)");
        createIndex("IDX_RAG_GRAPH_CATEGORY", "CREATE INDEX IDX_RAG_GRAPH_CATEGORY ON RAG_GRAPH_TRIPLE (TENANT_ID, CATEGORY)");
    }

    private void createIndex(String name, String ddl) {
        try {
            jdbc.execute(ddl);
        } catch (RuntimeException ignored) {
            // Index already exists. Different JDBC drivers report this differently.
        }
    }

    @Override
    public void add(List<Triple> triples) {
        if (triples == null || triples.isEmpty()) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        for (Triple triple : triples) {
            String graphId = graphId(triple);
            jdbc.update("DELETE FROM RAG_GRAPH_TRIPLE WHERE GRAPH_ID=?", graphId);
            jdbc.update("""
                            INSERT INTO RAG_GRAPH_TRIPLE
                            (GRAPH_ID, TENANT_ID, CATEGORY, SUBJECT_TEXT, SUBJECT_KEY, RELATION_TEXT,
                             OBJECT_TEXT, OBJECT_KEY, SOURCE_ID, CREATED_AT)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
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
            triples = jdbc.query("""
                    SELECT SUBJECT_TEXT, RELATION_TEXT, OBJECT_TEXT, SOURCE_ID, TENANT_ID, CATEGORY
                    FROM RAG_GRAPH_TRIPLE
                    WHERE TENANT_ID=?
                    ORDER BY CREATED_AT ASC, GRAPH_ID ASC""", this::mapTriple, tenantId);
        } else {
            triples = jdbc.query("""
                    SELECT SUBJECT_TEXT, RELATION_TEXT, OBJECT_TEXT, SOURCE_ID, TENANT_ID, CATEGORY
                    FROM RAG_GRAPH_TRIPLE
                    WHERE TENANT_ID=? AND CATEGORY=?
                    ORDER BY CREATED_AT ASC, GRAPH_ID ASC""", this::mapTriple, tenantId, category);
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
        return jdbc.update("DELETE FROM RAG_GRAPH_TRIPLE WHERE TENANT_ID=? AND SOURCE_ID LIKE ?",
                tenantId, escapeLike(sourceIdPrefix) + "%");
    }

    @Override
    public int size() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM RAG_GRAPH_TRIPLE", Integer.class);
        return count == null ? 0 : count;
    }

    private List<Triple> triplesForEntity(String tenantId, String entityKey, String category) {
        if (category == null || category.isBlank()) {
            return jdbc.query("""
                    SELECT SUBJECT_TEXT, RELATION_TEXT, OBJECT_TEXT, SOURCE_ID, TENANT_ID, CATEGORY
                    FROM RAG_GRAPH_TRIPLE
                    WHERE TENANT_ID=? AND (SUBJECT_KEY=? OR OBJECT_KEY=?)
                    ORDER BY CREATED_AT ASC, GRAPH_ID ASC""", this::mapTriple, tenantId, entityKey, entityKey);
        }
        return jdbc.query("""
                SELECT SUBJECT_TEXT, RELATION_TEXT, OBJECT_TEXT, SOURCE_ID, TENANT_ID, CATEGORY
                FROM RAG_GRAPH_TRIPLE
                WHERE TENANT_ID=? AND CATEGORY=? AND (SUBJECT_KEY=? OR OBJECT_KEY=?)
                ORDER BY CREATED_AT ASC, GRAPH_ID ASC""", this::mapTriple, tenantId, category, entityKey, entityKey);
    }

    private Triple mapTriple(ResultSet rs, int rowNum) throws SQLException {
        return new Triple(
                rs.getString("SUBJECT_TEXT"),
                rs.getString("RELATION_TEXT"),
                rs.getString("OBJECT_TEXT"),
                rs.getString("SOURCE_ID"),
                rs.getString("TENANT_ID"),
                rs.getString("CATEGORY"));
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
