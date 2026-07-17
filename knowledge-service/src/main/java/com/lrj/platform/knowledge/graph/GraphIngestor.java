package com.lrj.platform.knowledge.graph;

import com.lrj.platform.knowledge.TaggedSourceContentInjector;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * GraphRAG 入库器：对文档切分后的 {@link TextSegment} 逐段调 {@link GraphExtractor} 抽取三元组，
 * 按关系白名单与每块上限过滤、按别名表归一化实体，附上 tenantId/category/sourceId 后写入 {@link GraphStore}；
 * 支持同步或经 {@link Executor} 异步入库。文档删除时按 sourceId 前缀清理对应三元组。
 */
public class GraphIngestor {

    private static final Logger log = LoggerFactory.getLogger(GraphIngestor.class);

    private final GraphExtractor extractor;
    private final GraphStore graphStore;
    private final int maxTriplesPerChunk;
    private final Set<String> relationWhitelist;
    private final Map<String, String> aliases;
    private final Executor executor;
    private final boolean async;

    public GraphIngestor(GraphExtractor extractor,
                         GraphStore graphStore,
                         int maxTriplesPerChunk,
                         Set<String> relationWhitelist,
                         Map<String, String> aliases,
                         Executor executor,
                         boolean async) {
        this.extractor = extractor;
        this.graphStore = graphStore;
        this.maxTriplesPerChunk = Math.max(1, maxTriplesPerChunk);
        this.relationWhitelist = relationWhitelist == null ? Set.of() : Set.copyOf(relationWhitelist);
        this.aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
        this.executor = executor == null ? Runnable::run : executor;
        this.async = async;
    }

    public void ingest(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return;
        }
        Runnable task = () -> ingestNow(segments);
        if (async) {
            executor.execute(task);
        } else {
            task.run();
        }
    }

    public int removeBySourcePrefix(String tenantId, String sourceIdPrefix) {
        return graphStore.removeBySourcePrefix(tenantId, sourceIdPrefix);
    }

    private void ingestNow(List<TextSegment> segments) {
        List<Triple> triples = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            ExtractedTriples extracted = extractor.extract(segment.text());
            String tenantId = segment.metadata().getString("tenantId");
            String category = segment.metadata().getString("category");
            String sourceId = TaggedSourceContentInjector.inferId(segment, i);
            int accepted = 0;
            for (RawTriple raw : extracted.triples()) {
                if (accepted >= maxTriplesPerChunk) {
                    break;
                }
                if (!raw.isComplete() || !allowed(raw.relation())) {
                    continue;
                }
                triples.add(new Triple(
                        canonical(raw.subject()),
                        raw.relation(),
                        canonical(raw.object()),
                        sourceId,
                        tenantId,
                        category));
                accepted++;
            }
        }
        graphStore.add(triples);
        if (!triples.isEmpty()) {
            log.info("ingested graph triples count={}", triples.size());
        }
    }

    private boolean allowed(String relation) {
        return relationWhitelist.isEmpty() || relationWhitelist.contains(relation);
    }

    private String canonical(String surface) {
        String value = surface == null ? "" : surface.trim();
        Set<String> seen = new LinkedHashSet<>();
        while (aliases.containsKey(value) && seen.add(value)) {
            value = aliases.get(value);
        }
        return value;
    }
}
