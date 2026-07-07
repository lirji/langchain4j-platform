package com.lrj.platform.gateway.cascade;

/**
 * 级联「谁最终作答」的指标回调（{@code served=cheap|strong}），用于量化省下多少次强模型调用。
 *
 * <p>做成轻量函数式接口而非直接依赖 {@code MeterRegistry}，让 platform-gateway-client 保持零新依赖
 * （micrometer 不进这个被所有服务共享的库）；使用方（如带 actuator 的 conversation-service）再提供
 * {@code MeterRegistry} 支撑的实现。默认 {@link #noop()} 不计量。
 */
@FunctionalInterface
public interface CascadeMetrics {

    /** @param served "cheap" | "strong"，谁最终作答。 */
    void served(String served);

    static CascadeMetrics noop() {
        return served -> {
        };
    }
}
