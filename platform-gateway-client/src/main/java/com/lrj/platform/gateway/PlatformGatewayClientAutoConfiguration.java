package com.lrj.platform.gateway;

import com.lrj.platform.gateway.tenant.EnvironmentTenantVirtualKeyResolver;
import com.lrj.platform.gateway.tenant.TenantAttributionMode;
import com.lrj.platform.gateway.tenant.TenantIdentityProvider;
import com.lrj.platform.gateway.tenant.TenantVirtualKeyResolver;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * 自动装配网关 ChatModel。服务只要依赖本模块 + 配 {@code platform.gateway.*} 即得一个走 LiteLLM 的
 * {@link ChatModel} Bean（业务 {@code @AiService} 直接注入）。
 *
 * <p>租户归因（{@code platform.gateway.tenant-attribution}，默认 none）：身份来自
 * {@link TenantIdentityProvider}（security 在 classpath 时由
 * {@code TenantContextIdentityAutoConfiguration} 提供，缺失时匿名兜底 + 非 none 档启动告警）；
 * virtual key 来自 {@link TenantVirtualKeyResolver}（默认从 Environment 读，业务 Bean 可覆盖）。
 * Tracer/Propagator 存在时（追踪显式开启）出站请求带 W3C trace 头。
 */
@Configuration
@EnableConfigurationProperties(GatewayClientProperties.class)
public class PlatformGatewayClientAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PlatformGatewayClientAutoConfiguration.class);

    /** 默认 virtual-key 解析器：Environment 逐 key 查询（不经 configprops 暴露）。业务 Bean 覆盖即生效。 */
    @Bean
    @ConditionalOnMissingBean(TenantVirtualKeyResolver.class)
    public TenantVirtualKeyResolver tenantVirtualKeyResolver(Environment environment) {
        return new EnvironmentTenantVirtualKeyResolver(environment);
    }

    @Bean
    public GatewayChatModelFactory gatewayChatModelFactory(GatewayClientProperties props,
                                                           List<ChatModelListener> listeners,
                                                           ObjectProvider<TenantIdentityProvider> identityProvider,
                                                           TenantVirtualKeyResolver keyResolver,
                                                           ObjectProvider<Tracer> tracer,
                                                           ObjectProvider<Propagator> propagator) {
        TenantAttributionMode mode = props.getTenantAttribution();
        TenantIdentityProvider identities = identityProvider.getIfAvailable();
        if (identities == null) {
            if (mode != TenantAttributionMode.NONE) {
                // 非 none 档却没有身份来源（如 eval-service 无 platform-security）：user 档全部归 anonymous；
                // virtual-key 档需显式配 anonymous key，否则调用期 fail-closed。宁可醒目告警，不静默装死。
                log.warn("platform.gateway.tenant-attribution={} 但 classpath 无 TenantIdentityProvider"
                        + "（通常因缺 platform-security）—— 所有 LLM 调用将归因为 anonymous", mode);
            }
            identities = GatewayChatModelFactory.ANONYMOUS_IDENTITY;
        }
        GatewayRequestHeadersSupplier headersSupplier = new GatewayRequestHeadersSupplier(
                mode, identities, keyResolver, tracer.getIfUnique(), propagator.getIfUnique());
        return new GatewayChatModelFactory(props, listeners, identities, headersSupplier);
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
