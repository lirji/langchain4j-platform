package com.lrj.platform.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * platform-observability 自动装配：出站 traceId 透传（框架无关）+ servlet 入站 traceId filter。
 */
@Configuration
public class PlatformObservabilityAutoConfiguration {

    @Bean
    public OutboundTraceForwarder outboundTraceForwarder() {
        return new OutboundTraceForwarder();
    }

    @Configuration
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ServletTraceConfig {
        @Bean
        public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
            FilterRegistrationBean<TraceIdFilter> reg = new FilterRegistrationBean<>(new TraceIdFilter());
            reg.addUrlPatterns("/*");
            reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            return reg;
        }
    }
}
