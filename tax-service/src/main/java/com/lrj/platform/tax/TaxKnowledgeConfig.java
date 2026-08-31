package com.lrj.platform.tax;

import com.lrj.platform.observability.OutboundTraceForwarder;
import com.lrj.platform.security.OutboundTenantForwarder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/** 财税知识检索装配；关闭时提供空实现，保持确定性规则可用。 */
@Configuration
public class TaxKnowledgeConfig {

    @Bean
    @ConditionalOnProperty(name = "app.tax.knowledge.enabled", havingValue = "true", matchIfMissing = true)
    RestTemplate taxKnowledgeRestTemplate(RestTemplateBuilder builder,
                                          OutboundTenantForwarder tenantForwarder,
                                          OutboundTraceForwarder traceForwarder,
                                          TaxReviewProperties properties) {
        var knowledge = properties.getKnowledge();
        return builder.rootUri(knowledge.getBaseUrl())
                .additionalInterceptors(tenantForwarder, traceForwarder)
                .setConnectTimeout(knowledge.getConnectTimeout())
                .setReadTimeout(knowledge.getReadTimeout())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.tax.knowledge.enabled", havingValue = "true", matchIfMissing = true)
    TaxKnowledgeClient httpTaxKnowledgeClient(RestTemplate taxKnowledgeRestTemplate,
                                              TaxReviewProperties properties) {
        return new HttpTaxKnowledgeClient(taxKnowledgeRestTemplate, properties.getKnowledge());
    }

    @Bean
    @ConditionalOnProperty(name = "app.tax.knowledge.enabled", havingValue = "false")
    TaxKnowledgeClient noTaxKnowledgeClient() {
        return outcome -> List.of();
    }
}
