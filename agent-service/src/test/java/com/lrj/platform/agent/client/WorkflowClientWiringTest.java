package com.lrj.platform.agent.client;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：workflow 默认关，但 agent 开启时仍须有一个 {@link WorkflowClient} 兜底 bean（{@link NoopWorkflowClient}），
 * 与 analytics 三件套同理，避免将来出现「常在」消费者时注入失败。开启 workflow 后由 {@link HttpWorkflowClient} 接管。
 */
class WorkflowClientWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NoopWorkflowClient.class, HttpWorkflowClient.class, StubRestTemplateConfig.class);

    @Test
    void workflowDisabled_registersNoopFallback() {
        runner.withPropertyValues("app.agent.enabled=true", "app.agent.workflow.enabled=false")
                .run(ctx -> assertThat(ctx).hasSingleBean(WorkflowClient.class)
                        .getBean(WorkflowClient.class).isInstanceOf(NoopWorkflowClient.class));
    }

    @Test
    void workflowEnabled_useHttpNotNoop() {
        runner.withPropertyValues("app.agent.enabled=true", "app.agent.workflow.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(NoopWorkflowClient.class);
                    assertThat(ctx).hasSingleBean(WorkflowClient.class)
                            .getBean(WorkflowClient.class).isInstanceOf(HttpWorkflowClient.class);
                });
    }

    @Test
    void agentDisabled_noWorkflowClientNeeded() {
        runner.withPropertyValues("app.agent.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(WorkflowClient.class));
    }

    @Configuration
    static class StubRestTemplateConfig {
        @Bean
        RestTemplate workflowRestTemplate() {
            return new RestTemplate();
        }
    }
}
