package com.lrj.platform.knowledge.graph;

import com.lrj.platform.knowledge.hybrid.KeywordTokenizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Configuration
@ConditionalOnProperty(name = "app.rag.graph.enabled", havingValue = "true")
public class GraphRagConfig {

    @Bean
    GraphStore graphStore() {
        return new InMemoryGraphStore();
    }

    @Bean
    GraphExtractor graphExtractor() {
        return new RuleBasedGraphExtractor();
    }

    @Bean
    EntityLinker entityLinker(GraphStore graphStore, KeywordTokenizer tokenizer) {
        return new TokenEntityLinker(graphStore, tokenizer);
    }

    @Bean
    GraphIngestor graphIngestor(GraphExtractor extractor,
                                GraphStore graphStore,
                                @Value("${app.rag.graph.max-triples-per-chunk:12}") int maxTriplesPerChunk,
                                @Value("${app.rag.graph.relation-whitelist:}") String relationWhitelist,
                                @Value("${app.rag.graph.aliases:}") String aliases,
                                @Value("${app.rag.graph.async:false}") boolean async) {
        Executor executor = Runnable::run;
        return new GraphIngestor(
                extractor,
                graphStore,
                maxTriplesPerChunk,
                parseSet(relationWhitelist),
                parseAliases(aliases),
                executor,
                async);
    }

    private static Set<String> parseSet(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, String> parseAliases(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> s.contains("="))
                .map(s -> s.split("=", 2))
                .filter(parts -> !parts[0].isBlank() && !parts[1].isBlank())
                .collect(Collectors.toUnmodifiableMap(parts -> parts[0].trim(), parts -> parts[1].trim()));
    }
}
