package com.lrj.platform.tax;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** 财税 AI 说明器装配。关闭开关时不调用模型。 */
@Configuration
public class TaxAiConfig {

    @Bean
    DeterministicTaxNarrator deterministicTaxNarrator() {
        return new DeterministicTaxNarrator();
    }

    @Bean
    @ConditionalOnProperty(name = "app.tax.ai.enabled", havingValue = "true", matchIfMissing = true)
    TaxAiAssistant taxAiAssistant(ChatModel chatModel) {
        return AiServices.builder(TaxAiAssistant.class).chatModel(chatModel).build();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.tax.ai.enabled", havingValue = "true", matchIfMissing = true)
    TaxNarrator aiTaxNarrator(TaxAiAssistant assistant, DeterministicTaxNarrator fallback) {
        return new AiTaxNarrator(assistant, fallback);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.tax.ai.enabled", havingValue = "false")
    TaxNarrator fallbackTaxNarrator(DeterministicTaxNarrator fallback) {
        return fallback;
    }
}
