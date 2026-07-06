package com.lrj.platform.knowledge.graph;

import com.lrj.platform.security.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
@ConditionalOnBean(GraphStore.class)
public class GraphSearchService {

    private final GraphStore graphStore;
    private final EntityLinker entityLinker;
    private final int defaultMaxHops;
    private final int defaultMaxTriples;

    public GraphSearchService(GraphStore graphStore,
                              EntityLinker entityLinker,
                              @Value("${app.rag.graph.max-hops:2}") int defaultMaxHops,
                              @Value("${app.rag.graph.max-triples:20}") int defaultMaxTriples) {
        this.graphStore = graphStore;
        this.entityLinker = entityLinker;
        this.defaultMaxHops = defaultMaxHops;
        this.defaultMaxTriples = defaultMaxTriples;
    }

    public GraphQueryResult query(String query, Integer maxHops, Integer maxTriples, String category) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        String tenantId = TenantContext.current().tenantId();
        int hops = maxHops != null && maxHops > 0 ? maxHops : defaultMaxHops;
        int limit = maxTriples != null && maxTriples > 0 ? maxTriples : defaultMaxTriples;
        Set<String> seeds = entityLinker.link(query, tenantId, category);
        List<GraphHit> hits = graphStore.neighbors(seeds, hops, tenantId, category).stream()
                .limit(limit)
                .map(GraphHit::from)
                .toList();
        return new GraphQueryResult(query, tenantId, seeds, hits);
    }

    public Set<String> entities(String category) {
        return graphStore.entities(TenantContext.current().tenantId(), category);
    }

    public int size() {
        return graphStore.size();
    }

    public record GraphQueryResult(String query, String tenantId, Set<String> seeds, List<GraphHit> hits) {}

    public record GraphHit(String subject,
                           String relation,
                           String object,
                           String sourceId,
                           String category,
                           String text) {
        static GraphHit from(Triple triple) {
            return new GraphHit(
                    triple.subject(),
                    triple.relation(),
                    triple.object(),
                    triple.sourceId(),
                    triple.category(),
                    triple.subject() + " --" + triple.relation() + "-> " + triple.object());
        }
    }
}
