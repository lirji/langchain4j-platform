package com.lrj.platform.conversation.cascade;

import com.lrj.platform.gateway.GatewayChatModelFactory;
import com.lrj.platform.gateway.GatewayClientProperties;
import com.lrj.platform.gateway.cascade.CascadeChatModel;
import com.lrj.platform.gateway.cascade.CascadeChatModelFactory;
import com.lrj.platform.gateway.cascade.CascadeMetrics;
import com.lrj.platform.gateway.cascade.CascadeProperties;
import com.lrj.platform.gateway.cascade.ConfidenceGate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Model Cascade 装配。<strong>整个 config 条件化在 {@code app.chat.cascade.enabled=true}</strong>——
 * 关闭（默认）时 cheap/strong 模型、{@link ConfidenceGate}、{@link CascadeService}、
 * {@code CascadeController} 全不装配，对现有 {@code /chat} 零影响。
 *
 * <p>cascade 的构造能力归属 platform-gateway-client（{@link CascadeChatModelFactory}）；本模块只提供
 * {@code app.chat.cascade.*} 绑定、指标接线（actuator 的 {@link MeterRegistry}）与 {@code /chat/cascade}
 * 端点。级联模型作为 {@link CascadeService} 的私有字段存在，<strong>不注册成第二个 ChatModel Bean</strong>。
 */
@Configuration
@ConditionalOnProperty(name = "app.chat.cascade.enabled", havingValue = "true")
public class CascadeConfig {

    static final String METRIC = "llm.cascade";

    @Bean
    @ConfigurationProperties(prefix = "app.chat.cascade")
    CascadeProperties cascadeProperties() {
        return new CascadeProperties();
    }

    @Bean
    CascadeChatModelFactory cascadeChatModelFactory(GatewayChatModelFactory gateway,
                                                    GatewayClientProperties gatewayProps) {
        return new CascadeChatModelFactory(gateway, gatewayProps);
    }

    @Bean
    ConfidenceGate cascadeConfidenceGate(CascadeProperties props, CascadeChatModelFactory factory) {
        // 自评模型（可选）：便宜模型 temp=0 自评。未开自评时 rater 为 null。
        return new ConfidenceGate(props, factory.buildRater(props));
    }

    @Bean
    CascadeService cascadeService(CascadeProperties props,
                                  ConfidenceGate gate,
                                  CascadeChatModelFactory factory,
                                  ObjectProvider<MeterRegistry> registry) {
        CascadeMetrics metrics = cascadeMetrics(registry.getIfAvailable());
        CascadeChatModel cascade = factory.build(props, gate, metrics);
        return new CascadeService(cascade);
    }

    /** llm.cascade{served=cheap|strong} 计数器——量化省下多少次强模型调用；无 MeterRegistry 时降级 noop。 */
    private static CascadeMetrics cascadeMetrics(MeterRegistry registry) {
        if (registry == null) {
            return CascadeMetrics.noop();
        }
        return served -> registry.counter(METRIC, Tags.of("served", served)).increment();
    }
}
