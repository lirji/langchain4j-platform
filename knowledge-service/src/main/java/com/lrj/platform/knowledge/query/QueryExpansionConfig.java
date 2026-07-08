package com.lrj.platform.knowledge.query;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 查询扩展装配。默认 {@link NoopQueryExpander}（不扩展）。开启
 * {@code app.rag.query-expansion.enabled=true} 后用共享 temp=0 ChatModel 生成变体。
 * {@code app.rag.query-expansion.max-variants}（默认 4，含原 query）。
 */
@Configuration
public class QueryExpansionConfig {

    private static final Logger log = LoggerFactory.getLogger(QueryExpansionConfig.class);

    @Bean
    @ConditionalOnProperty(name = "app.rag.query-expansion.enabled", havingValue = "false", matchIfMissing = true)
    QueryExpander noopQueryExpander() {
        return new NoopQueryExpander();
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.query-expansion.enabled", havingValue = "true")
    QueryExpander llmQueryExpander(ChatModel knowledgeChatModel,
                                   @Value("${app.rag.query-expansion.max-variants:4}") int maxVariants) {
        log.info("RAG query-expansion: llm enabled (maxVariants={})", maxVariants);
        return new LlmQueryExpander(knowledgeChatModel::chat, maxVariants);
    }
}
