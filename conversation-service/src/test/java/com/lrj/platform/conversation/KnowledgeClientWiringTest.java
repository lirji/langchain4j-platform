package com.lrj.platform.conversation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：RAG 关闭/缺省时必须仍有一个 {@link KnowledgeClient} 兜底 bean，否则
 * {@link RagPromptAugmenter} 的构造注入失败、整个 conversation-service 起不来。
 *
 * <p>历史缺陷：{@link NoopKnowledgeClient} 曾用 {@code @ConditionalOnMissingBean}，
 * 在组件扫描下注册顺序不可靠，导致默认(RAG off)配置下无 KnowledgeClient bean。
 */
class KnowledgeClientWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NoopKnowledgeClient.class, HttpKnowledgeClient.class);

    @Test
    void ragDisabled_registersNoopFallback() {
        runner.withPropertyValues("app.conversation.rag.enabled=false")
                .run(ctx -> assertThat(ctx).hasSingleBean(KnowledgeClient.class)
                        .getBean(KnowledgeClient.class).isInstanceOf(NoopKnowledgeClient.class));
    }

    @Test
    void ragPropertyMissing_registersNoopFallback() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(KnowledgeClient.class)
                .getBean(KnowledgeClient.class).isInstanceOf(NoopKnowledgeClient.class));
    }

    @Test
    void ragEnabled_usesHttpNotNoop() {
        // 开启时 Noop 让位，Http 变体接管（此处提供桩 RestTemplate 模拟 ConversationRagConfig 的 bean）。
        runner.withUserConfiguration(StubRestTemplateConfig.class)
                .withPropertyValues("app.conversation.rag.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(NoopKnowledgeClient.class);
                    assertThat(ctx).hasSingleBean(KnowledgeClient.class)
                            .getBean(KnowledgeClient.class).isInstanceOf(HttpKnowledgeClient.class);
                });
    }

    @Configuration
    static class StubRestTemplateConfig {
        @Bean
        RestTemplate knowledgeRestTemplate() {
            return new RestTemplate();
        }
    }
}
