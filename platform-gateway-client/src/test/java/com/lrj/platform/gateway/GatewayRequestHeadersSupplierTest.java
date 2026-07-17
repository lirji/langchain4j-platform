package com.lrj.platform.gateway;

import com.lrj.platform.gateway.tenant.TenantAttributionMode;
import com.lrj.platform.gateway.tenant.TenantVirtualKeyMissingException;
import com.lrj.platform.gateway.tenant.TenantVirtualKeyResolver;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link GatewayRequestHeadersSupplier}：virtual-key fail-closed、trace 注入、每次新 Map、
 * 异常不含 key 内容。
 */
class GatewayRequestHeadersSupplierTest {

    private static final TenantVirtualKeyResolver KEYS = tenantId ->
            "tenant-a".equals(tenantId) ? Optional.of("sk-va-key-a") : Optional.empty();

    @Test
    void none_withoutTracing_returnsEmptyMap_freshInstanceEachCall() {
        GatewayRequestHeadersSupplier supplier = new GatewayRequestHeadersSupplier(
                TenantAttributionMode.NONE, () -> "tenant-a", KEYS, null, null);

        Map<String, String> first = supplier.get();
        assertThat(first).isEmpty();
        first.put("X-Poison", "v"); // 调用方污染不得影响下一次
        assertThat(supplier.get()).isEmpty();
    }

    @Test
    void user_doesNotTouchAuthorization() {
        GatewayRequestHeadersSupplier supplier = new GatewayRequestHeadersSupplier(
                TenantAttributionMode.USER, () -> "tenant-a", KEYS, null, null);

        assertThat(supplier.get()).doesNotContainKey("Authorization");
    }

    @Test
    void virtualKey_setsBearerAuthorization() {
        GatewayRequestHeadersSupplier supplier = new GatewayRequestHeadersSupplier(
                TenantAttributionMode.VIRTUAL_KEY, () -> "tenant-a", KEYS, null, null);

        assertThat(supplier.get()).containsEntry("Authorization", "Bearer sk-va-key-a");
    }

    @Test
    void virtualKey_missingKey_failsClosed_withoutLeakingSecrets() {
        GatewayRequestHeadersSupplier supplier = new GatewayRequestHeadersSupplier(
                TenantAttributionMode.VIRTUAL_KEY, () -> "tenant-unknown", KEYS, null, null);

        assertThatThrownBy(supplier::get)
                .isInstanceOf(TenantVirtualKeyMissingException.class)
                .hasMessageContaining("tenant-unknown")
                .hasMessageNotContaining("sk-"); // 异常消息绝不含任何 key 内容
    }

    @Test
    void virtualKey_blankKeyFromCustomResolver_alsoFailsClosed() {
        // 自定义 resolver 违反 trim 契约返回空白 —— supplier 兜底拦截，不发空白 Bearer 触达 LiteLLM
        TenantVirtualKeyResolver sloppy = tenantId -> Optional.of("   ");
        GatewayRequestHeadersSupplier supplier = new GatewayRequestHeadersSupplier(
                TenantAttributionMode.VIRTUAL_KEY, () -> "tenant-a", sloppy, null, null);

        assertThatThrownBy(supplier::get).isInstanceOf(TenantVirtualKeyMissingException.class);
    }

    @Test
    void tracing_injectsW3cHeaders_alongsideVirtualKey() {
        Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentTraceContext().context()).thenReturn(context);

        Propagator propagator = mock(Propagator.class);
        doAnswer(invocation -> {
            Map<String, String> carrier = invocation.getArgument(1);
            Propagator.Setter<Map<String, String>> setter = invocation.getArgument(2);
            setter.set(carrier, "traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
            return null;
        }).when(propagator).inject(eq(context), any(), any());

        GatewayRequestHeadersSupplier supplier = new GatewayRequestHeadersSupplier(
                TenantAttributionMode.VIRTUAL_KEY, () -> "tenant-a", KEYS, tracer, propagator);

        Map<String, String> headers = supplier.get();
        // 两类 header 合并共存，键不相交互不覆盖
        assertThat(headers).containsEntry("Authorization", "Bearer sk-va-key-a")
                .containsKey("traceparent");
    }

    @Test
    void tracing_noActiveSpan_skipsInjection() {
        Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
        when(tracer.currentTraceContext().context()).thenReturn(null);

        GatewayRequestHeadersSupplier supplier = new GatewayRequestHeadersSupplier(
                TenantAttributionMode.NONE, () -> "tenant-a", KEYS, tracer, mock(Propagator.class));

        assertThat(supplier.get()).isEmpty();
    }
}
