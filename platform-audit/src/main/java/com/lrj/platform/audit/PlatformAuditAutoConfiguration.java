package com.lrj.platform.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlatformAuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AuditLogger auditLogger(ObjectMapper mapper) {
        return new AuditLogger(mapper);
    }

    @Bean
    @ConditionalOnMissingBean(AuditChatModelListener.class)
    public ChatModelListener auditChatModelListener(AuditLogger auditLogger) {
        return new AuditChatModelListener(auditLogger);
    }
}
