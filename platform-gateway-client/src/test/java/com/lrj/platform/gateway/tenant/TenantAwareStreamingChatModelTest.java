package com.lrj.platform.gateway.tenant;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TenantAwareStreamingChatModel}：三参覆盖点归因 + 两参默认方法漏斗 + NONE 透传。
 * 归因发生在发起调用的线程（TenantContext 可取），与 SSE 回调线程无关。
 */
class TenantAwareStreamingChatModelTest {

    /** 捕获 wrapper 递交请求的流式 fake（覆盖三参 chat，不真正流式）。 */
    static class CapturingStreamingChatModel implements StreamingChatModel {
        final AtomicReference<ChatRequest> seen = new AtomicReference<>();

        @Override
        public void chat(ChatRequest request, ChatRequestOptions options, StreamingChatResponseHandler handler) {
            seen.set(request);
        }
    }

    private static final StreamingChatResponseHandler NOOP_HANDLER = new StreamingChatResponseHandler() {
        @Override
        public void onPartialResponse(String token) { }

        @Override
        public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) { }

        @Override
        public void onError(Throwable error) { }
    };

    @Test
    void none_passesRequestThroughUntouched() {
        CapturingStreamingChatModel delegate = new CapturingStreamingChatModel();
        TenantAwareStreamingChatModel model =
                new TenantAwareStreamingChatModel(delegate, TenantAttributionMode.NONE, () -> "tenant-a");

        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("hi")).build();
        model.chat(request, ChatRequestOptions.EMPTY, NOOP_HANDLER);

        assertThat(delegate.seen.get()).isSameAs(request);
    }

    @Test
    void user_attributesTenant_onThreeArgOverride() {
        CapturingStreamingChatModel delegate = new CapturingStreamingChatModel();
        TenantAwareStreamingChatModel model =
                new TenantAwareStreamingChatModel(delegate, TenantAttributionMode.USER, () -> "tenant-s");

        model.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build(),
                ChatRequestOptions.EMPTY, NOOP_HANDLER);

        OpenAiChatRequestParameters params = (OpenAiChatRequestParameters) delegate.seen.get().parameters();
        assertThat(params.user()).isEqualTo("tenant-s");
    }

    @Test
    void twoArgOverload_funnelsThroughAttribution() {
        CapturingStreamingChatModel delegate = new CapturingStreamingChatModel();
        TenantAwareStreamingChatModel model =
                new TenantAwareStreamingChatModel(delegate, TenantAttributionMode.VIRTUAL_KEY, () -> "tenant-v");

        // 两参 default → 三参（EMPTY options）→ wrapper 覆盖点（字节码核验的漏斗）
        model.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build(), NOOP_HANDLER);

        OpenAiChatRequestParameters params = (OpenAiChatRequestParameters) delegate.seen.get().parameters();
        assertThat(params.user()).isEqualTo("tenant-v");
    }
}
