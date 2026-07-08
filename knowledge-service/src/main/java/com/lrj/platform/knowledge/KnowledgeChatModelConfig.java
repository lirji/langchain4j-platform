package com.lrj.platform.knowledge;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * knowledge-service 共享的确定性 ChatModel（temp=0），供各可选 LLM 增强复用：
 * 重排（{@code rerank}）、查询扩展（{@code query}）、Grounding 事实校验、Contextual 入库前缀。
 *
 * <p>沿用 {@code GatewaySemanticCacheEmbedder} 的做法：直接建指向 LiteLLM 网关的 OpenAI-compat client，
 * 默认复用 {@code platform.gateway.*}。构造期不发网络请求，仅在对应增强开启并实际调用时才走网关。
 * 这些增强默认全关，故常规运行/单测下该 Bean 虽存在但从不被调用，零回归。
 */
@Configuration
public class KnowledgeChatModelConfig {

    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    ChatModel knowledgeChatModel(
            @Value("${app.rag.llm.base-url:${platform.gateway.base-url:http://localhost:4000/v1}}") String baseUrl,
            @Value("${app.rag.llm.api-key:${platform.gateway.api-key:sk-litellm-master}}") String apiKey,
            @Value("${app.rag.llm.model-name:${platform.gateway.model-name:chat-default}}") String modelName,
            @Value("${app.rag.llm.timeout:60s}") String timeout) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.0)
                .timeout(DurationStyle.detectAndParse(timeout))
                .build();
    }
}
