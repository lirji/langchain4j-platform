package com.lrj.platform.knowledge.graph;

import com.lrj.platform.knowledge.hybrid.KeywordTokenizer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Configuration
@ConditionalOnProperty(name = "app.rag.graph.enabled", havingValue = "true")
public class GraphRagConfig {

    @Bean
    @ConditionalOnProperty(name = "app.rag.graph.store", havingValue = "in-memory", matchIfMissing = true)
    GraphStore graphStore() {
        return new InMemoryGraphStore();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.rag.graph.datasource")
    @ConditionalOnProperty(name = "app.rag.graph.store", havingValue = "jdbc")
    GraphDatasourceProperties graphDatasourceProperties() {
        return new GraphDatasourceProperties();
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.graph.store", havingValue = "jdbc")
    DataSource graphDataSource(GraphDatasourceProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getUrl());
        config.setDriverClassName(properties.getDriverClassName());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setMaximumPoolSize(properties.getMaximumPoolSize());
        config.setPoolName("knowledge-graph");
        return new HikariDataSource(config);
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.graph.store", havingValue = "jdbc")
    GraphStore jdbcGraphStore(DataSource graphDataSource) {
        return new JdbcGraphStore(graphDataSource);
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
