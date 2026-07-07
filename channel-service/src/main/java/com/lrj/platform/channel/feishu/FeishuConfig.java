package com.lrj.platform.channel.feishu;

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
 * 飞书事件桥装配。仅 {@code app.channel.feishu.enabled=true} 时生效——默认关，对现有 channel 零影响。
 */
@Configuration
@EnableConfigurationProperties(FeishuProperties.class)
@ConditionalOnProperty(prefix = "app.channel.feishu", name = "enabled", havingValue = "true")
public class FeishuConfig {

    @Bean
    FeishuEventCrypto feishuEventCrypto(FeishuProperties props) {
        return new FeishuEventCrypto(props.getEncryptKey());
    }

    /** 桥 → conversation：带租户/trace 转发器（铸发内部 JWT），rootUri 指向 conversation。 */
    @Bean
    RestTemplate feishuConversationRestTemplate(RestTemplateBuilder builder,
                                                OutboundTenantForwarder tenantForwarder,
                                                OutboundTraceForwarder traceForwarder,
                                                FeishuProperties props) {
        return builder
                .rootUri(props.getConversationBaseUrl())
                .additionalInterceptors(tenantForwarder, traceForwarder)
                .setConnectTimeout(props.getConnectTimeout())
                .setReadTimeout(props.getReadTimeout())
                .build();
    }

    /** 桥 → 飞书开放平台：普通外呼，不带内部转发器。 */
    @Bean
    RestTemplate feishuReplyRestTemplate(RestTemplateBuilder builder, FeishuProperties props) {
        return builder
                .setConnectTimeout(props.getConnectTimeout())
                .setReadTimeout(props.getReadTimeout())
                .build();
    }

    @Bean
    HttpConversationClient httpConversationClient(RestTemplate feishuConversationRestTemplate) {
        return new HttpConversationClient(feishuConversationRestTemplate);
    }

    @Bean
    HttpFeishuReplyClient httpFeishuReplyClient(RestTemplate feishuReplyRestTemplate,
                                                FeishuProperties props, ObjectMapper json) {
        return new HttpFeishuReplyClient(feishuReplyRestTemplate, props, json);
    }

    @Bean
    Executor feishuBridgeExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "feishu-bridge");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    FeishuMessageBridge feishuMessageBridge(HttpConversationClient conversation,
                                            HttpFeishuReplyClient reply,
                                            Executor feishuBridgeExecutor,
                                            FeishuProperties props) {
        return new FeishuMessageBridge(conversation, reply, feishuBridgeExecutor, props);
    }
}
