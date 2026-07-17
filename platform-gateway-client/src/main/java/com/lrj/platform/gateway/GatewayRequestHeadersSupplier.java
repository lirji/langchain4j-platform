package com.lrj.platform.gateway;

import com.lrj.platform.gateway.tenant.TenantAttributionMode;
import com.lrj.platform.gateway.tenant.TenantIdentityProvider;
import com.lrj.platform.gateway.tenant.TenantVirtualKeyMissingException;
import com.lrj.platform.gateway.tenant.TenantVirtualKeyResolver;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 每次出站 LLM 请求动态合成 HTTP header（挂到 OpenAI builder 的
 * {@code customHeaders(Supplier)}，langchain4j 每次发送时求值）：
 *
 * <ol>
 *   <li><strong>virtual-key Authorization</strong>（仅 {@link TenantAttributionMode#VIRTUAL_KEY}）：
 *       当前租户的 LiteLLM virtual key 覆盖静态 master key；key 缺失抛
 *       {@link TenantVirtualKeyMissingException} —— 在发起 provider 调用前 fail-closed，
 *       绝不回退 master key。</li>
 *   <li><strong>W3C trace 传播</strong>（任意模式，Tracer/Propagator Bean 存在且有活跃 span 时）：
 *       注入 {@code traceparent}/{@code tracestate}，让 LiteLLM 的 OTel span 与 Java 侧同 trace。
 *       追踪默认关（见 {@link TracingDefaultsEnvironmentPostProcessor}），关着时两者为 null，零开销。</li>
 * </ol>
 *
 * <p>每次调用返回新 {@link LinkedHashMap}（互不污染）；两类 header 键不相交，互不覆盖。
 * 异常与日志均不含 key 内容。
 */
public class GatewayRequestHeadersSupplier implements Supplier<Map<String, String>> {

    private final TenantAttributionMode mode;
    private final TenantIdentityProvider identities;
    private final TenantVirtualKeyResolver keyResolver;
    private final Tracer tracer;          // 可为 null（追踪默认关）
    private final Propagator propagator;  // 可为 null

    public GatewayRequestHeadersSupplier(TenantAttributionMode mode,
                                         TenantIdentityProvider identities,
                                         TenantVirtualKeyResolver keyResolver,
                                         Tracer tracer,
                                         Propagator propagator) {
        this.mode = mode;
        this.identities = identities;
        this.keyResolver = keyResolver;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public Map<String, String> get() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (mode == TenantAttributionMode.VIRTUAL_KEY) {
            String tenantId = identities.currentTenantId();
            String key = keyResolver.resolve(tenantId)
                    .map(String::trim)
                    .filter(k -> !k.isEmpty())   // 自定义 resolver 返回空白也算缺失 —— 不发空白 Bearer
                    .orElseThrow(() -> new TenantVirtualKeyMissingException(tenantId));
            headers.put("Authorization", "Bearer " + key);
        }
        if (tracer != null && propagator != null && tracer.currentTraceContext() != null) {
            TraceContext context = tracer.currentTraceContext().context();
            if (context != null) {
                propagator.inject(context, headers, Map::put);
            }
        }
        return headers;
    }
}
