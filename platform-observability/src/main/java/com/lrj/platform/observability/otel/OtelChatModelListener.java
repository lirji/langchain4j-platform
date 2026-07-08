package com.lrj.platform.observability.otel;

import com.lrj.platform.security.TenantContext;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import java.util.function.Supplier;

/**
 * 把每次 chat-model 调用记成一条 micrometer-tracing {@code CLIENT} span，属性遵循 OpenTelemetry GenAI
 * 语义约定（{@code gen_ai.system} / {@code gen_ai.request.model} / {@code gen_ai.usage.input_tokens} 等）。
 * 移植单体 {@code observability/otel/OtelChatModelListener}，span API 从原生 OTel 换成 micrometer-tracing。
 *
 * <p>作为 {@code ChatModelListener} bean 由 {@code GatewayChatModelFactory} 收集进 {@code List<ChatModelListener>}，
 * 同 audit / metering 挂载，无需改网关工厂。
 *
 * <p>{@link Tracer} 经 {@link Supplier} <strong>惰性解析</strong>：仅当下游服务开启 Boot tracing
 * （{@code management.tracing.enabled=true} + {@code management.otlp.tracing.endpoint}）时才存在 Tracer bean，
 * 否则 supplier 返回 {@code null}、本 listener 全程 no-op、零开销、启动不失败。
 *
 * <p>span 在 {@link #onRequest} 起、{@link #onResponse}/{@link #onError} 收，span 句柄经 {@code ctx.attributes()}
 * 跨回调传递（同 audit listener 存开始时间戳的套路）。micrometer 的 tag 只收 String，故数值统一 toString。
 */
public class OtelChatModelListener implements ChatModelListener {

    /** span 句柄在 ctx.attributes() 里的 key。 */
    private static final String SPAN_KEY = "lrj.otel.span";
    /** 记录调用开始纳秒，用于算 duration 属性。 */
    private static final String START_KEY = "lrj.otel.startNanos";

    private final Supplier<Tracer> tracerSupplier;

    public OtelChatModelListener(Supplier<Tracer> tracerSupplier) {
        this.tracerSupplier = tracerSupplier;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        Tracer tracer = tracerSupplier.get();
        if (tracer == null) {
            return; // 未开启 tracing：无 Tracer bean，直接跳过（零开销）。
        }
        String model = safe(ctx.chatRequest().parameters().modelName());
        String provider = system(ctx.modelProvider() == null ? null : ctx.modelProvider().name());

        // GenAI 约定 span 名：{operation} {model}
        Span span = tracer.spanBuilder()
                .name("chat " + model)
                .kind(Span.Kind.CLIENT)
                .start();
        span.tag("gen_ai.operation.name", "chat");
        span.tag("gen_ai.system", provider);
        span.tag("gen_ai.request.model", model);
        span.tag("gen_ai.request.messages", String.valueOf(ctx.chatRequest().messages().size()));

        // 租户归属：复用现有 TenantContext（onRequest 跑在业务请求线程上，ThreadLocal 尚在）
        TenantContext.Tenant t = TenantContext.current();
        if (t != null) {
            span.tag("tenant.id", safe(t.tenantId()));
            span.tag("enduser.id", safe(t.userId()));
        }

        ctx.attributes().put(SPAN_KEY, span);
        ctx.attributes().put(START_KEY, System.nanoTime());
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        Span span = span(ctx.attributes().get(SPAN_KEY));
        if (span == null) {
            return;
        }
        try {
            ChatResponseMetadata md = ctx.chatResponse().metadata();
            if (md.modelName() != null) {
                span.tag("gen_ai.response.model", md.modelName());
            }
            if (md.finishReason() != null) {
                span.tag("gen_ai.response.finish_reasons", md.finishReason().toString());
            }
            TokenUsage tu = md.tokenUsage();
            if (tu != null) {
                if (tu.inputTokenCount() != null) {
                    span.tag("gen_ai.usage.input_tokens", String.valueOf(tu.inputTokenCount()));
                }
                if (tu.outputTokenCount() != null) {
                    span.tag("gen_ai.usage.output_tokens", String.valueOf(tu.outputTokenCount()));
                }
            }
            recordDuration(span, ctx.attributes().get(START_KEY));
        } finally {
            span.end();
        }
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        Span span = span(ctx.attributes().get(SPAN_KEY));
        if (span == null) {
            return;
        }
        try {
            Throwable err = ctx.error();
            span.tag("error.type", err.getClass().getName());
            span.error(err);
            recordDuration(span, ctx.attributes().get(START_KEY));
        } finally {
            span.end();
        }
    }

    private static void recordDuration(Span span, Object startNanos) {
        if (startNanos instanceof Long start) {
            span.tag("gen_ai.client.duration_ms", String.valueOf((System.nanoTime() - start) / 1_000_000));
        }
    }

    private static Span span(Object o) {
        return o instanceof Span s ? s : null;
    }

    /** ModelProvider 枚举名 → GenAI {@code gen_ai.system} 惯用小写值（OPEN_AI → openai）。 */
    private static String system(String providerName) {
        if (providerName == null) {
            return "unknown";
        }
        return switch (providerName) {
            case "OPEN_AI" -> "openai";
            case "ANTHROPIC" -> "anthropic";
            case "GOOGLE_AI_GEMINI", "GOOGLE_VERTEX_AI_GEMINI" -> "gemini";
            case "MISTRAL_AI" -> "mistral_ai";
            case "AMAZON_BEDROCK" -> "aws.bedrock";
            case "OLLAMA" -> "ollama";
            default -> providerName.toLowerCase();
        };
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s;
    }
}
