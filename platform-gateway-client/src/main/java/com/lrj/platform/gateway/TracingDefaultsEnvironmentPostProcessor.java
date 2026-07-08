package com.lrj.platform.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 让所有依赖 platform-gateway-client 的 LLM 服务**默认关闭分布式追踪**（零回归）。
 *
 * <p>gateway-client 带来了 {@code micrometer-tracing-bridge-otel} + OTLP exporter，Spring Boot 默认
 * 会自动装配 tracing（{@code management.tracing.enabled} 默认 true）→ 产生 {@code Tracer} bean、每请求建 span，
 * 即便没配 collector 也有开销。这里以**最低优先级**（{@code addLast}）注入默认
 * {@code management.tracing.enabled=false}，使追踪默认关、零开销。
 *
 * <p>需要开启时，在服务 yml/env 显式设 {@code management.tracing.enabled=true} 并配
 * {@code management.otlp.tracing.endpoint}（覆盖本兜底），届时
 * {@code com.lrj.platform.observability.otel.OtelChatModelListener} 会自动挂上每次 LLM 调用的 GenAI span。
 */
public class TracingDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // addLast = 最低优先级：任何 yml / env / 命令行显式设置都胜出。
        environment.getPropertySources().addLast(new MapPropertySource(
                "platformGatewayTracingDefaults",
                Map.of("management.tracing.enabled", "false")));
    }
}
