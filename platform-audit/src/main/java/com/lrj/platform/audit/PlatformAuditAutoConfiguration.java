package com.lrj.platform.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * platform-audit 审计子系统的自动装配：注册 {@link AuditLogger}，并把审计埋点挂到 langchain4j 的
 * {@link ChatModelListener} SPI（{@link AuditChatModelListener}），从而对每次 LLM 调用记录审计日志。
 */
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
