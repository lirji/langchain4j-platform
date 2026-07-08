package com.lrj.platform.conversation.extract;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 程序化构建 {@link Extractor}（对齐单体 {@code ExtractorConfig}）：用主网关 {@link ChatModel}，
 * 不挂 ChatMemory / ContentRetriever —— 一次性无状态抽取。端点常开。
 */
@Configuration
public class ExtractorConfig {

    @Bean
    Extractor extractor(ChatModel chatModel) {
        return AiServices.builder(Extractor.class)
                .chatModel(chatModel)
                .build();
    }
}
