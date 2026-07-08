package com.lrj.platform.knowledge.ingest;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contextual Retrieval 入库增强装配。默认 {@link NoopContextualEnricher}（不增强）。
 * {@code app.rag.contextual.enabled=true} 开启后用共享 temp=0 ChatModel 逐 chunk 生成上下文前缀。
 * {@code app.rag.contextual.max-doc-chars}（默认 8000）控制喂给生成器的文档截断上限。
 */
@Configuration
public class ContextualEnrichmentConfig {

    private static final Logger log = LoggerFactory.getLogger(ContextualEnrichmentConfig.class);

    @Bean
    @ConditionalOnProperty(name = "app.rag.contextual.enabled", havingValue = "false", matchIfMissing = true)
    ContextualEnricher noopContextualEnricher() {
        return new NoopContextualEnricher();
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.contextual.enabled", havingValue = "true")
    ContextualEnricher llmContextualEnricher(ChatModel knowledgeChatModel,
                                             @Value("${app.rag.contextual.max-doc-chars:8000}") int maxDocChars) {
        log.info("RAG contextual retrieval: enabled (maxDocChars={})", maxDocChars);
        ChunkContextualizer contextualizer =
                (docText, chunkText) -> knowledgeChatModel.chat(prompt(docText, chunkText));
        return new LlmContextualEnricher(contextualizer, maxDocChars);
    }

    static String prompt(String docText, String chunkText) {
        return """
                下面是一篇文档的全文，以及其中的一个片段。请用一句简短中文说明该片段在全文中的位置与主题
                （消解代词/缩写、点明它讲的是什么），便于该片段脱离全文也能被准确检索。只输出这一句上下文，不要复述片段原文。

                <文档>
                %s
                </文档>

                <片段>
                %s
                </片段>
                """.formatted(docText, chunkText);
    }
}
