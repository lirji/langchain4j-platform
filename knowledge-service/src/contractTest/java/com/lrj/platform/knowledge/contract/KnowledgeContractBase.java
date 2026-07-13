package com.lrj.platform.knowledge.contract;

import com.lrj.platform.knowledge.KnowledgeQueryService;
import com.lrj.platform.knowledge.controller.KnowledgeQueryController;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spring Cloud Contract 自动生成的 knowledge provider 测试的基类。
 *
 * <p>刻意贴合本仓「纯 POJO 单测」文化：用 {@link RestAssuredMockMvc#standaloneSetup} +
 * mock 掉 {@link KnowledgeQueryService}，不起全量 Spring context。生成的测试对
 * {@code src/contractTest/resources/contracts/knowledge} 下的每份契约发起真实 MockMvc 请求。
 *
 * <p>本类仅在 {@code -Pcontract} profile（build-helper 追加 {@code src/contractTest/java} 源根）下参与编译，
 * 默认 {@code mvn test} 完全不触达。
 */
public abstract class KnowledgeContractBase {

    @BeforeEach
    void setup() {
        KnowledgeQueryService service = mock(KnowledgeQueryService.class);

        var hit = new KnowledgeQueryService.Hit(
                "doc-1#0", 0.87, "doc-1", "Refund Manual", "manual", "0",
                "Refunds are processed within 7 days.", "vector", false);
        var result = new KnowledgeQueryService.QueryResult("refund policy", "acme", List.of(hit));
        when(service.query(eq("refund policy"), eq(3), eq(0.2), eq("manual"))).thenReturn(result);

        // 空 query 契约：service 抛 IllegalArgumentException，controller 翻成 400。
        when(service.query(eq(" "), eq(null), eq(null), eq(null)))
                .thenThrow(new IllegalArgumentException("query is required"));

        RestAssuredMockMvc.standaloneSetup(new KnowledgeQueryController(service));
    }
}
