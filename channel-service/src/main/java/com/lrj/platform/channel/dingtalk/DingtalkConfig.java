package com.lrj.platform.channel.dingtalk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.observability.OutboundTraceForwarder;
import com.lrj.platform.security.OutboundTenantForwarder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 钉钉知识库客服桥装配。仅 {@code app.channel.dingtalk.enabled=true} 时生效——默认关，对现有 channel 零影响。
 */
@Configuration
@EnableConfigurationProperties(DingtalkProperties.class)
@ConditionalOnProperty(prefix = "app.channel.dingtalk", name = "enabled", havingValue = "true")
public class DingtalkConfig {

    @Bean
    DingtalkEventCrypto dingtalkEventCrypto(DingtalkProperties props) {
        return new DingtalkEventCrypto(props.getAppSecret());
    }

    /** 桥 → conversation：带租户/trace 转发器（铸发内部 JWT），rootUri 指向 conversation。 */
    @Bean
    RestTemplate dingtalkConversationRestTemplate(RestTemplateBuilder builder,
                                                  OutboundTenantForwarder tenantForwarder,
                                                  OutboundTraceForwarder traceForwarder,
                                                  DingtalkProperties props) {
        return builder
                .rootUri(props.getConversationBaseUrl())
                .additionalInterceptors(tenantForwarder, traceForwarder)
                .setConnectTimeout(props.getConnectTimeout())
                .setReadTimeout(props.getReadTimeout())
                .build();
    }

    /** 桥 → knowledge：兜底闸门查 /rag/query，同样带租户/trace 转发器。 */
    @Bean
    RestTemplate dingtalkKnowledgeRestTemplate(RestTemplateBuilder builder,
                                               OutboundTenantForwarder tenantForwarder,
                                               OutboundTraceForwarder traceForwarder,
                                               DingtalkProperties props) {
        return builder
                .rootUri(props.getKnowledgeBaseUrl())
                .additionalInterceptors(tenantForwarder, traceForwarder)
                .setConnectTimeout(props.getConnectTimeout())
                .setReadTimeout(props.getReadTimeout())
                .build();
    }

    /** 桥 → 钉钉开放平台：普通外呼，不带内部转发器。 */
    @Bean
    RestTemplate dingtalkReplyRestTemplate(RestTemplateBuilder builder, DingtalkProperties props) {
        return builder
                .setConnectTimeout(props.getConnectTimeout())
                .setReadTimeout(props.getReadTimeout())
                .build();
    }

    @Bean
    DingtalkConversationClient dingtalkConversationClient(RestTemplate dingtalkConversationRestTemplate) {
        return new DingtalkConversationClient(dingtalkConversationRestTemplate);
    }

    @Bean
    DingtalkKnowledgeClient dingtalkKnowledgeClient(RestTemplate dingtalkKnowledgeRestTemplate) {
        return new DingtalkKnowledgeClient(dingtalkKnowledgeRestTemplate);
    }

    @Bean
    HttpDingtalkReplyClient httpDingtalkReplyClient(RestTemplate dingtalkReplyRestTemplate,
                                                    DingtalkProperties props, ObjectMapper json) {
        return new HttpDingtalkReplyClient(dingtalkReplyRestTemplate, props, json);
    }

    @Bean
    Executor dingtalkBridgeExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "dingtalk-bridge");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    DingtalkMessageBridge dingtalkMessageBridge(DingtalkConversationClient dingtalkConversationClient,
                                                DingtalkKnowledgeClient dingtalkKnowledgeClient,
                                                HttpDingtalkReplyClient httpDingtalkReplyClient,
                                                Executor dingtalkBridgeExecutor,
                                                DingtalkProperties props) {
        return new DingtalkMessageBridge(dingtalkConversationClient, dingtalkKnowledgeClient,
                httpDingtalkReplyClient, dingtalkBridgeExecutor, props);
    }
}
