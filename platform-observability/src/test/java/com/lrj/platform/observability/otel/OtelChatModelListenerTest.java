package com.lrj.platform.observability.otel;

import com.lrj.platform.security.TenantContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OtelChatModelListener 的确定性行为：把一次 chat 调用记成带 GenAI tag 的 span。
 * 用 micrometer-tracing 的 SimpleTracer 断言，不连模型 / collector / 网络 / 真 OTLP。
 */
class OtelChatModelListenerTest {

    private SimpleTracer tracer;
    private OtelChatModelListener listener;

    @BeforeEach
    void setUp() {
        tracer = new SimpleTracer();
        listener = new OtelChatModelListener(() -> tracer);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static ChatRequest request(String model) {
        return ChatRequest.builder()
                .messages(UserMessage.from("你好"))
                .modelName(model)
                .build();
    }

    @Test
    void emitsSpanWithGenAiTagsOnResponse() {
        TenantContext.set(new TenantContext.Tenant("acme", "u-1", Set.of()));
        Map<Object, Object> attrs = new HashMap<>();

        listener.onRequest(new ChatModelRequestContext(
                request("gpt-4o-mini"), ModelProvider.OPEN_AI, attrs));

        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("你好，有什么可以帮你？"))
                .metadata(ChatResponseMetadata.builder()
                        .modelName("gpt-4o-mini")
                        .tokenUsage(new TokenUsage(12, 8))
                        .finishReason(FinishReason.STOP)
                        .build())
                .build();
        listener.onResponse(new ChatModelResponseContext(
                response, request("gpt-4o-mini"), ModelProvider.OPEN_AI, attrs));

        assertThat(tracer.getSpans()).hasSize(1);
        SimpleSpan span = tracer.getSpans().getFirst();
        assertThat(span.getName()).isEqualTo("chat gpt-4o-mini");

        Map<String, String> tags = span.getTags();
        assertThat(tags).containsEntry("gen_ai.operation.name", "chat");
        assertThat(tags).containsEntry("gen_ai.system", "openai");
        assertThat(tags).containsEntry("gen_ai.request.model", "gpt-4o-mini");
        assertThat(tags).containsEntry("gen_ai.usage.input_tokens", "12");
        assertThat(tags).containsEntry("gen_ai.usage.output_tokens", "8");
        assertThat(tags).containsEntry("gen_ai.response.finish_reasons", "STOP");
        assertThat(tags).containsEntry("tenant.id", "acme");
        assertThat(tags).containsEntry("enduser.id", "u-1");
    }

    @Test
    void emitsErrorSpanOnError() {
        Map<Object, Object> attrs = new HashMap<>();
        listener.onRequest(new ChatModelRequestContext(
                request("llama3.1"), ModelProvider.OLLAMA, attrs));
        listener.onError(new ChatModelErrorContext(
                new RuntimeException("boom"), request("llama3.1"), ModelProvider.OLLAMA, attrs));

        assertThat(tracer.getSpans()).hasSize(1);
        SimpleSpan span = tracer.getSpans().getFirst();
        assertThat(span.getTags()).containsEntry("error.type", "java.lang.RuntimeException");
        assertThat(span.getTags()).containsEntry("gen_ai.system", "ollama");
    }

    @Test
    void noTracerAvailableEmitsNothing() {
        // 关闭态：supplier 返回 null（无 Tracer bean），listener 全程 no-op。
        OtelChatModelListener noop = new OtelChatModelListener(() -> null);
        Map<Object, Object> attrs = new HashMap<>();
        noop.onRequest(new ChatModelRequestContext(
                request("gpt-4o-mini"), ModelProvider.OPEN_AI, attrs));
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("hi"))
                .metadata(ChatResponseMetadata.builder().modelName("gpt-4o-mini").build())
                .build();
        noop.onResponse(new ChatModelResponseContext(
                response, request("gpt-4o-mini"), ModelProvider.OPEN_AI, attrs));

        assertThat(tracer.getSpans()).isEmpty();
        assertThat(attrs).doesNotContainKey("lrj.otel.span");
    }
}
