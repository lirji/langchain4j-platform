package com.lrj.platform.gateway;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 自动装配网关 ChatModel。服务只要依赖本模块 + 配 {@code platform.gateway.*} 即得一个走 LiteLLM 的
 * {@link ChatModel} Bean（业务 {@code @AiService} 直接注入）。
 */
@Configuration
@EnableConfigurationProperties(GatewayClientProperties.class)
public class PlatformGatewayClientAutoConfiguration {

    @Bean
    public GatewayChatModelFactory gatewayChatModelFactory(GatewayClientProperties props,
                                                           List<ChatModelListener> listeners) {
        return new GatewayChatModelFactory(props, listeners);
    }

    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel chatModel(GatewayChatModelFactory factory) {
        return factory.build();
    }

    /**
     * 流式 {@link StreamingChatModel} Bean（token 级 SSE）。默认开启；服务不需要流式时可用
     * {@code platform.gateway.streaming.enabled=false} 关掉以省一个 Bean。业务 {@code @AiService}
     * 的 {@code TokenStream} 方法自动注入它。
     */
    @Bean
    @ConditionalOnMissingBean(StreamingChatModel.class)
    @ConditionalOnProperty(name = "platform.gateway.streaming.enabled", havingValue = "true", matchIfMissing = true)
    public StreamingChatModel streamingChatModel(GatewayChatModelFactory factory) {
        return factory.buildStreaming();
    }
}
