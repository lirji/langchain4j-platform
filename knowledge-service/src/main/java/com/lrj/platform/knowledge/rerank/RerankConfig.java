package com.lrj.platform.knowledge.rerank;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.jina.JinaScoringModel;
import dev.langchain4j.model.scoring.ScoringModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 重排器装配。默认 {@link NoopReranker}（不 rerank）。开启后按 {@code app.rag.rerank.type} 二选一：
 * {@code llm}（复用共享 temp=0 ChatModel 打分，零外部依赖）| {@code jina}（Jina reranker 云 API + Key）。
 *
 * <pre>
 * app.rag.rerank.enabled=true
 * app.rag.rerank.type=llm|jina
 * app.rag.rerank.candidate-multiplier=3   # 召回放大倍数（rerank 候选池）
 * </pre>
 */
@Configuration
public class RerankConfig {

    private static final Logger log = LoggerFactory.getLogger(RerankConfig.class);

    @Bean
    @ConditionalOnProperty(name = "app.rag.rerank.enabled", havingValue = "false", matchIfMissing = true)
    Reranker noopReranker() {
        return new NoopReranker();
    }

    @Bean
    @ConditionalOnExpression("${app.rag.rerank.enabled:false} and '${app.rag.rerank.type:llm}'.equals('llm')")
    Reranker llmReranker(ChatModel knowledgeChatModel,
                         @Value("${app.rag.rerank.candidate-multiplier:3}") int multiplier) {
        log.info("RAG rerank: llm-as-judge enabled (candidate x{})", multiplier);
        RelevanceScorer scorer = (query, text) -> parseScore(
                knowledgeChatModel.chat(scorePrompt(query, text)));
        return new LlmReranker(scorer, multiplier);
    }

    @Bean
    @ConditionalOnExpression("${app.rag.rerank.enabled:false} and '${app.rag.rerank.type:llm}'.equals('jina')")
    Reranker jinaReranker(
            @Value("${app.rag.rerank.jina.api-key:${JINA_API_KEY:}}") String apiKey,
            @Value("${app.rag.rerank.jina.model-name:jina-reranker-v2-base-multilingual}") String modelName,
            @Value("${app.rag.rerank.candidate-multiplier:3}") int multiplier) {
        log.info("RAG rerank: jina enabled model={} (candidate x{})", modelName, multiplier);
        ScoringModel scoringModel = JinaScoringModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
        return new JinaReranker(scoringModel, multiplier);
    }

    /** LLM 打分提示：只输出 0..1 的一个小数表示相关性。 */
    static String scorePrompt(String query, String text) {
        return """
                你是相关性判官。给定[问题]与[文档片段]，只输出一个 0 到 1 之间的小数，表示该片段回答该问题的相关性，
                1 表示高度相关、0 表示完全无关。除这个小数外不要输出任何其它字符。

                [问题]
                %s

                [文档片段]
                %s
                """.formatted(query, text);
    }

    /** 从模型输出里解析首个 0..1 小数；解析失败返回 0。 */
    static double parseScore(String modelOutput) {
        if (modelOutput == null) {
            return 0.0;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?<!\\d)(0(?:\\.\\d+)?|1(?:\\.0+)?)").matcher(modelOutput.trim());
        if (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1));
                return Math.max(0.0, Math.min(1.0, v));
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }
}
