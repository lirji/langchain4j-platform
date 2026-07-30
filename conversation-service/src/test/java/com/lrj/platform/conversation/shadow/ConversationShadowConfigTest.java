package com.lrj.platform.conversation.shadow;

import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationShadowConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(ConversationShadowConfig.class);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        MDC.clear();
    }

    @Test
    void shadowExecutorPropagatesTenantAndTraceWithoutLeakingBack() throws Exception {
        ConversationShadowConfig config = new ConversationShadowConfig();
        Executor executor = config.conversationShadowExecutor(1, 1, 4);
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        MDC.put("traceId", "trace-1");

        CompletableFuture<String> observed = new CompletableFuture<>();
        executor.execute(() -> observed.complete(
                TenantContext.current().tenantId() + ":"
                        + TenantContext.current().userId() + ":"
                        + MDC.get("traceId")));

        assertThat(observed.get(2, TimeUnit.SECONDS)).isEqualTo("acme:alice:trace-1");
        assertThat(TenantContext.current().tenantId()).isEqualTo("acme");
        assertThat(MDC.get("traceId")).isEqualTo("trace-1");
        ((ThreadPoolTaskExecutor) executor).shutdown();
    }

    @Test
    void enabledShadowRequiresExplicitCandidateBaseUrl() {
        ConversationShadowConfig config = new ConversationShadowConfig();

        assertThatThrownBy(() -> config.conversationShadowRestTemplate(
                new RestTemplateBuilder(), null, null, " ", java.time.Duration.ofMillis(10),
                java.time.Duration.ofMillis(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.conversation.shadow.base-url");
    }

    @Test
    void shadowIsNoopByDefaultWithoutCandidateDependencies() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ConversationShadowObserver.class);
            assertThat(context).doesNotHaveBean("conversationShadowRestTemplate");
            assertThat(context).doesNotHaveBean("conversationShadowExecutor");
        });
    }
}
