package com.lrj.platform.conversation.routing;

import com.lrj.platform.conversation.Assistant;
import com.lrj.platform.conversation.RagPromptAugmenter;
import com.lrj.platform.conversation.prompt.ResolvedAssistantStyle;
import com.lrj.platform.gateway.GatewayChatModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM-as-Router 装配。默认关；开启后构建 {@link QueryClassifier}（temp=0 判官模型，分类需确定性）
 * 与 {@link QueryRouterService}。分类器/服务缺失时 {@code /chat/auto} 返回禁用提示（见 controller）。
 */
@Configuration
@ConditionalOnProperty(name = "app.conversation.router.enabled", havingValue = "true")
public class RoutingConfig {

    private static final Logger log = LoggerFactory.getLogger(RoutingConfig.class);

    @Bean
    QueryClassifier queryClassifier(GatewayChatModelFactory factory) {
        ChatModel judge = factory.buildDeterministic();
        log.info("LLM-as-Router: enabled (deterministic classifier)");
        return AiServices.builder(QueryClassifier.class).chatModel(judge).build();
    }

    @Bean
    QueryRouterService queryRouterService(QueryClassifier classifier, Assistant assistant,
                                          RagPromptAugmenter ragPromptAugmenter, ResolvedAssistantStyle style,
                                          OrderQueryRoute orderQueryRoute) {
        return new QueryRouterService(classifier, assistant, ragPromptAugmenter, style, orderQueryRoute);
    }
}
