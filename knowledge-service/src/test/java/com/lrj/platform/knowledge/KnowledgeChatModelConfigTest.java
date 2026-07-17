package com.lrj.platform.knowledge;

import com.lrj.platform.gateway.GatewayChatModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 锁定修复：knowledge 的共享 RAG 增强 ChatModel 必须经 {@link GatewayChatModelFactory} 收口构造，
 * 而非早前自建 {@code OpenAiChatModel}（那样会绕过审计 / 按租户 token 预算 / 成本 / LiteLLM 侧租户归因）。
 * 同时温度必须为 0（打分 / 扩展 / 前缀生成需确定性），且该 Bean 已去掉 {@code @ConditionalOnMissingBean}。
 */
class KnowledgeChatModelConfigTest {

    @Test
    void knowledgeChatModel_builtViaFactory_withDeterministicTemperature() {
        GatewayChatModelFactory factory = mock(GatewayChatModelFactory.class);
        ChatModel built = mock(ChatModel.class);
        when(factory.build("rerank-mini", 0.0)).thenReturn(built);

        ChatModel result = new KnowledgeChatModelConfig()
                .knowledgeChatModel(factory, "rerank-mini");

        // 返回的正是工厂产物（携带 listeners + TenantAware 包装），而非本地手搓模型
        assertThat(result).isSameAs(built);
        // temp 固定 0，且只走 factory.build(modelName, temperature) 这一个出口
        verify(factory).build("rerank-mini", 0.0);
        verifyNoMoreInteractions(factory);
    }

    @Test
    void knowledgeChatModel_isUnconditionalApplicationBeanWithExpectedSignature()
            throws NoSuchMethodException {
        Method method = KnowledgeChatModelConfig.class.getDeclaredMethod(
                "knowledgeChatModel", GatewayChatModelFactory.class, String.class);

        assertThat(method.isAnnotationPresent(Bean.class)).isTrue();
        // 收口修复的显式目标：删除 @ConditionalOnMissingBean，让它成为应用侧唯一无条件 ChatModel Bean
        assertThat(method.isAnnotationPresent(ConditionalOnMissingBean.class)).isFalse();
        assertThat(method.getReturnType()).isEqualTo(ChatModel.class);
        assertThat(method.getParameterTypes())
                .containsExactly(GatewayChatModelFactory.class, String.class);
    }
}
