package com.lrj.platform.conversation.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 启动时按 {@code platform.gateway.model-name}（LiteLLM 逻辑模型名）解析出 {@link ResolvedAssistantStyle} bean。
 * 逻辑模型是启动期决策，故解析一次即可；改模型或改风格覆盖需重启。
 */
@Configuration
@EnableConfigurationProperties(AssistantStyleProperties.class)
public class AssistantStyleConfig {

    private static final Logger log = LoggerFactory.getLogger(AssistantStyleConfig.class);

    @Bean
    ResolvedAssistantStyle resolvedAssistantStyle(
            AssistantStyleProperties props,
            @Value("${platform.gateway.model-name:chat-default}") String modelName) {
        boolean overridden = props.getOverrides() != null && props.getOverrides().containsKey(modelName);
        log.info("ResolvedAssistantStyle for model={} (override={})", modelName, overridden);
        return props.resolve(modelName);
    }
}
