package com.lrj.platform.knowledge;

import com.lrj.platform.gateway.GatewayChatModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * knowledge-service 共享的确定性 ChatModel（temp=0），供各可选 LLM 增强复用：
 * 重排（{@code rerank}）、查询扩展（{@code query}）、Grounding 事实校验、Contextual 入库前缀。
 *
 * <p>经 {@link GatewayChatModelFactory} 收口构造 —— 与 conversation / agent 等服务同一路径，
 * 从而 <strong>审计 / 按租户 token 预算 / 成本核算</strong>（{@code ChatModelListener}）与
 * <strong>LiteLLM 侧租户归因</strong>（{@code platform.gateway.tenant-attribution}）对这些增强的
 * LLM 调用统一生效；不再像早前自建 {@code OpenAiChatModel} 那样绕过全部横切。
 *
 * <p>base-url / api-key / timeout / max-retries 一律取 {@code platform.gateway.*}；仅保留
 * {@code app.rag.llm.model-name}（{@code RAG_LLM_MODEL}）覆盖，允许增强用比主对话更廉价的模型。
 * 温度固定 0（打分 / 扩展 / 前缀生成需确定性），与工厂主模型温度解耦。
 *
 * <p>这些增强默认全关，故常规运行 / 单测下该 Bean 虽存在但从不被调用，零回归。它作为应用侧唯一的
 * {@code ChatModel} Bean，自动装配的默认 {@code chatModel} 因 {@code @ConditionalOnMissingBean}
 * 让位（knowledge 不需要主对话模型）。
 */
@Configuration
public class KnowledgeChatModelConfig {

    @Bean
    ChatModel knowledgeChatModel(
            GatewayChatModelFactory factory,
            @Value("${app.rag.llm.model-name:${platform.gateway.model-name:chat-default}}") String modelName) {
        return factory.build(modelName, 0.0);
    }
}
