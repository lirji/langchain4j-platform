package com.lrj.platform.agent.actions;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.code-exec.enabled"}, havingValue = "true")
public class CodeExecConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.agent.code-exec")
    CodeExecProperties codeExecProperties() {
        return new CodeExecProperties();
    }
}
