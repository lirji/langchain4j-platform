package com.lrj.platform.knowledge.graph;

import com.lrj.platform.knowledge.authz.AuthzMode;
import com.lrj.platform.knowledge.authz.KnowledgeAuthz;
import com.lrj.platform.knowledge.authz.NoopKnowledgeAuthz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/rag/graph")
// 与 GraphSearchService 同属性 gate，取代 @ConditionalOnBean（组件扫描下顺序不可靠，会随机启动失败）。
@ConditionalOnProperty(name = "app.rag.graph.enabled", havingValue = "true")
public class GraphController {

    private final GraphSearchService graphSearchService;
    // 细粒度授权（接 auth-platform）：默认 Noop；enforce 时图谱端点 fail-closed（三元组/实体无 docId 溯源，无法资源级判权）。
    private KnowledgeAuthz knowledgeAuthz = new NoopKnowledgeAuthz();

    public GraphController(GraphSearchService graphSearchService) {
        this.graphSearchService = graphSearchService;
    }

    @Autowired(required = false)
    public void setKnowledgeAuthz(KnowledgeAuthz knowledgeAuthz) {
        if (knowledgeAuthz != null) {
            this.knowledgeAuthz = knowledgeAuthz;
        }
    }

    @PostMapping("/query")
    public GraphSearchService.GraphQueryResult query(@RequestBody GraphQueryRequest request) {
        requireResourceAuthzNotEnforced();
        return graphSearchService.query(
                request.query(),
                request.maxHops(),
                request.maxTriples(),
                request.category());
    }

    @GetMapping("/entities")
    public Map<String, Object> entities(@RequestParam(required = false) String category) {
        requireResourceAuthzNotEnforced();
        return Map.of(
                "category", category == null ? "" : category,
                "entities", graphSearchService.entities(category),
                "size", graphSearchService.size());
    }

    /**
     * 图谱三元组/实体无 docId 溯源，无法做资源级 ReBAC；enforce 下 fail-closed，避免跨用户泄露无权文档内容。
     * shadow/disabled 保持原行为。图谱数据的资源级判权需数据侧带上来源 docId（后续项）。
     */
    private void requireResourceAuthzNotEnforced() {
        if (knowledgeAuthz.mode() == AuthzMode.ENFORCE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "graph endpoints lack resource-level ReBAC (triples have no docId); disabled under enforce mode");
        }
    }

    public record GraphQueryRequest(String query, Integer maxHops, Integer maxTriples, String category) {}
}
