package com.lrj.platform.observability.otel;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * 把 GenAI chat span 挂进任意开启了 Spring Boot 分布式追踪的下游服务（#1 OTel）。
 *
 * <p>仅当 classpath 同时有 micrometer-tracing {@link Tracer} 与 langchain4j {@link ChatModelListener}
 * 时才装配（即依赖 platform-gateway-client 的 6 个 LLM 服务；edge-gateway/config-server 等无这些类，
 * 经 {@link ConditionalOnClass} 整体跳过，零负担）。
 *
 * <p>listener 通过 {@link ObjectProvider} <strong>惰性</strong>拿 {@link Tracer}，规避 bean 装配时序：
 * 未开启 tracing（{@code management.tracing.enabled=false}，见 gateway-client 的
 * {@code TracingDefaultsEnvironmentPostProcessor} 默认）时无 Tracer bean、listener 全程 no-op；
 * 开启后 {@code getIfAvailable()} 返回 Boot 装配的 OTel-backed Tracer，span 经 OTLP 导出。
 */
@AutoConfiguration
@ConditionalOnClass(name = {
        "io.micrometer.tracing.Tracer",
        "dev.langchain4j.model.chat.listener.ChatModelListener"
})
public class OtelTracingAutoConfiguration {

    @Bean
    public ChatModelListener otelChatModelListener(ObjectProvider<Tracer> tracers) {
        return new OtelChatModelListener(tracers::getIfAvailable);
    }
}
