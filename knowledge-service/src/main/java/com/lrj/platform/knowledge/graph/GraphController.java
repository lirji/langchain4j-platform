package com.lrj.platform.knowledge.graph;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/rag/graph")
@ConditionalOnBean(GraphSearchService.class)
public class GraphController {

    private final GraphSearchService graphSearchService;

    public GraphController(GraphSearchService graphSearchService) {
        this.graphSearchService = graphSearchService;
    }

    @PostMapping("/query")
    public GraphSearchService.GraphQueryResult query(@RequestBody GraphQueryRequest request) {
        return graphSearchService.query(
                request.query(),
                request.maxHops(),
                request.maxTriples(),
                request.category());
    }

    @GetMapping("/entities")
    public Map<String, Object> entities(@RequestParam(required = false) String category) {
        return Map.of(
                "category", category == null ? "" : category,
                "entities", graphSearchService.entities(category),
                "size", graphSearchService.size());
    }

    public record GraphQueryRequest(String query, Integer maxHops, Integer maxTriples, String category) {}
}
