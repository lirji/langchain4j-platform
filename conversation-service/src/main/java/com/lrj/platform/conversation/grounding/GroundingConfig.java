package com.lrj.platform.conversation.grounding;

import com.lrj.platform.gateway.GatewayChatModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Grounding 事后校验装配。沿用平台成对 {@code @ConditionalOnProperty} 约定：
 * 关（默认）→ {@link NoopGroundingChecker}；开 → {@link LlmGroundingChecker}
 * （Layer1 faithfulness 用网关 temp=0 判官模型 {@link GatewayChatModelFactory#buildDeterministic()}）。
 *
 * <p>开启后每轮 RAG 回答多一次判官 LLM 调用（换取幻觉/低支撑度告警），故默认关；warn 模式只追加提示、不改写答案。
 */
@Configuration
public class GroundingConfig {

    private static final Logger log = LoggerFactory.getLogger(GroundingConfig.class);

    /** faithfulness 判官提示：只输出 0..1 支撑度分数（RAGAS 风格）。 */
    static String faithfulnessPrompt(String sources, String answer) {
        return """
                你是严格的 RAG faithfulness 判官。给定检索到的[来源]（<source> 包裹）与一段[答案]，
                判断答案里的事实断言在多大程度上被来源支撑：把答案拆成原子事实断言，
                支撑度 = 被来源支撑的断言数 / 总断言数。
                只输出一个 0 到 1 之间的小数（1=全部被支撑，0=全是来源没有的内容；答案若无可核实事实断言记 1）。
                除这个小数外不要输出任何其它字符。

                [来源]
                %s

                [答案]
                %s
                """.formatted(sources, answer);
    }

    @Bean
    @ConditionalOnProperty(name = "app.conversation.grounding.enabled", havingValue = "false", matchIfMissing = true)
    GroundingChecker noopGroundingChecker() {
        return new NoopGroundingChecker();
    }

    @Bean
    @ConditionalOnProperty(name = "app.conversation.grounding.enabled", havingValue = "true")
    GroundingChecker llmGroundingChecker(
            GatewayChatModelFactory factory,
            @Value("${app.conversation.grounding.threshold:0.7}") double threshold) {
        ChatModel judge = factory.buildDeterministic();
        log.info("Grounding (warn mode): enabled (threshold={})", threshold);
        FaithfulnessScorer scorer = (sources, answer) ->
                GroundingRules.parseScore(judge.chat(faithfulnessPrompt(sources, answer)));
        return new LlmGroundingChecker(scorer, threshold);
    }
}
