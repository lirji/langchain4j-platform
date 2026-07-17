package com.lrj.platform.knowledge.graph;

import com.lrj.platform.knowledge.hybrid.SimpleKeywordTokenizer;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GraphSearchServiceTest：验证 {@link GraphSearchService} 从查询文本链接种子实体、按租户与 category
 * 在 {@link InMemoryGraphStore} 上做邻居遍历并生成命中路径文本，确保跨租户与跨 category 的隔离。
 * 依赖 {@link TenantContext}，每个用例后在 {@code @AfterEach} 清理。
 */
class GraphSearchServiceTest {

    private final InMemoryGraphStore store = new InMemoryGraphStore();
    private final GraphSearchService service = new GraphSearchService(
            store,
            new TokenEntityLinker(store, new SimpleKeywordTokenizer()),
            2,
            20);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void graphQueryLinksEntitiesAndTraversesNeighborsByTenant() {
        store.add(List.of(
                new Triple("张三", "隶属于", "研发部", "people.md#0", "acme", "org"),
                new Triple("研发部", "使用", "LangChain4j", "tech.md#0", "acme", "org"),
                new Triple("张三", "隶属于", "财务部", "other.md#0", "globex", "org")));
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        GraphSearchService.GraphQueryResult result = service.query("张三负责什么团队", 2, 10, "org");

        assertThat(result.seeds()).containsExactly("张三");
        assertThat(result.hits())
                .extracting(GraphSearchService.GraphHit::text)
                .containsExactly(
                        "张三 --隶属于-> 研发部",
                        "研发部 --使用-> LangChain4j");
    }

    @Test
    void categoryFilterIsAppliedToEntitiesAndQuery() {
        store.add(List.of(
                new Triple("张三", "隶属于", "研发部", "people.md#0", "acme", "org"),
                new Triple("张三", "提到", "预算", "finance.md#0", "acme", "finance")));
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        assertThat(service.entities("finance")).containsExactly("张三", "预算");
        assertThat(service.query("张三", 1, 10, "finance").hits())
                .extracting(GraphSearchService.GraphHit::object)
                .containsExactly("预算");
    }
}
