package com.lrj.platform.agent.contract;

import com.lrj.platform.agent.AgentController;
import com.lrj.platform.agent.DeepAgentService;
import com.lrj.platform.security.TenantContext;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spring Cloud Contract 自动生成的 agent provider 测试的基类。
 *
 * <p>用 {@link RestAssuredMockMvc#standaloneSetup} + mock 掉 {@link DeepAgentService}，不起全量 Spring context。
 * {@code AgentRunMapper} 从 {@link TenantContext} 回填 {@code tenantId}，故此处显式设置租户并在结束清理
 * （遵循本仓 ThreadLocal 约定）。
 *
 * <p>本类仅在 {@code -Pcontract} profile 下参与编译，默认 {@code mvn test} 不触达。
 */
public abstract class AgentContractBase {

    @BeforeEach
    void setup() {
        DeepAgentService agent = mock(DeepAgentService.class);

        var step = new DeepAgentService.Step(
                1, "check the refund manual", "rag_search", "refund policy", "Refunds within 7 days.");
        var run = new DeepAgentService.Run(
                "summarize refund policy", List.of(step),
                "Refunds are issued within 7 days.", "DONE", 0);
        when(agent.run(eq("summarize refund policy"))).thenReturn(run);

        TenantContext.set(new TenantContext.Tenant("acme", "user-1", Set.of()));
        RestAssuredMockMvc.standaloneSetup(new AgentController(agent));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }
}
