package com.lrj.platform.gateway.tenant;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TenantAwareChatModel} 纯 POJO 单测：三档语义、user 防伪造、参数逐字保留、
 * 便捷重载漏斗、listener 只回调一次、并发不串租户。
 */
class TenantAwareChatModelTest {

    /** 捕获 wrapper 递交的原始请求（覆盖两参 chat，绕过 delegate 默认合并逻辑）。 */
    static class CapturingChatModel implements ChatModel {
        final AtomicReference<ChatRequest> seen = new AtomicReference<>();

        @Override
        public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
            seen.set(request);
            String user = request.parameters() instanceof OpenAiChatRequestParameters p ? p.user() : null;
            return ChatResponse.builder().aiMessage(AiMessage.from(String.valueOf(user))).build();
        }
    }

    private static ChatRequest requestWithForgedUser() {
        return ChatRequest.builder()
                .messages(UserMessage.from("hi"))
                .parameters(OpenAiChatRequestParameters.builder()
                        .modelName("chat-x")
                        .temperature(0.3)
                        .user("attacker-forged")
                        .build())
                .build();
    }

    @Test
    void none_passesRequestThroughUntouched() {
        CapturingChatModel delegate = new CapturingChatModel();
        TenantAwareChatModel model = new TenantAwareChatModel(delegate, TenantAttributionMode.NONE, () -> "tenant-a");

        ChatRequest request = requestWithForgedUser();
        model.chat(request, ChatRequestOptions.EMPTY);

        // NONE：同一实例原样透传（与接入前逐字一致），连伪造 user 也不动 —— 归因逻辑完全不参与
        assertThat(delegate.seen.get()).isSameAs(request);
    }

    @Test
    void user_overridesUserWithTrustedTenant_andKeepsOtherParams() {
        CapturingChatModel delegate = new CapturingChatModel();
        TenantAwareChatModel model = new TenantAwareChatModel(delegate, TenantAttributionMode.USER, () -> "tenant-a");

        model.chat(requestWithForgedUser(), ChatRequestOptions.EMPTY);

        OpenAiChatRequestParameters params = (OpenAiChatRequestParameters) delegate.seen.get().parameters();
        assertThat(params.user()).isEqualTo("tenant-a");            // 调用方伪造的 user 被可信身份覆盖
        assertThat(params.modelName()).isEqualTo("chat-x");         // 其余参数逐字保留
        assertThat(params.temperature()).isEqualTo(0.3);
        assertThat(delegate.seen.get().messages()).hasSize(1);
    }

    @Test
    void virtualKey_alsoAttributesUser() {
        CapturingChatModel delegate = new CapturingChatModel();
        TenantAwareChatModel model = new TenantAwareChatModel(delegate, TenantAttributionMode.VIRTUAL_KEY, () -> "tenant-b");

        model.chat(requestWithForgedUser(), ChatRequestOptions.EMPTY);

        OpenAiChatRequestParameters params = (OpenAiChatRequestParameters) delegate.seen.get().parameters();
        assertThat(params.user()).isEqualTo("tenant-b");
    }

    @Test
    void convenienceOverloads_funnelThroughAttribution() {
        CapturingChatModel delegate = new CapturingChatModel();
        TenantAwareChatModel model = new TenantAwareChatModel(delegate, TenantAttributionMode.USER, () -> "tenant-a");

        // 单参 chat(ChatRequest) 默认方法 → 两参 chat(request, EMPTY) → wrapper 覆盖点
        model.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build());

        OpenAiChatRequestParameters params = (OpenAiChatRequestParameters) delegate.seen.get().parameters();
        assertThat(params.user()).isEqualTo("tenant-a");
    }

    @Test
    void attributedRequest_setsUserWhenRequestHadPlainParameters() {
        // 非 OpenAi 参数类型的请求（AiServices 常见）也能归因 —— overrideWith 接受基类
        ChatRequest plain = ChatRequest.builder().messages(UserMessage.from("hi")).build();
        ChatRequest attributed = TenantAwareChatModel.attributedRequest(plain, "tenant-c");

        assertThat(((OpenAiChatRequestParameters) attributed.parameters()).user()).isEqualTo("tenant-c");
        assertThat(attributed.messages()).isEqualTo(plain.messages());
    }

    @Test
    void listenersFireExactlyOnce_throughWrapper() {
        AtomicInteger onRequest = new AtomicInteger();
        AtomicInteger onResponse = new AtomicInteger();
        ChatModelListener counting = new ChatModelListener() {
            @Override
            public void onRequest(ChatModelRequestContext ctx) {
                onRequest.incrementAndGet();
            }

            @Override
            public void onResponse(ChatModelResponseContext ctx) {
                onResponse.incrementAndGet();
            }
        };
        // delegate 只覆盖 doChat + listeners —— listener 边界留在 delegate 的两参 default chat 里
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
            }

            @Override
            public List<ChatModelListener> listeners() {
                return List.of(counting);
            }
        };
        TenantAwareChatModel model = new TenantAwareChatModel(delegate, TenantAttributionMode.USER, () -> "tenant-a");

        model.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build());

        // wrapper 覆盖两参 chat 直接委托 —— 自己的 default listener 边界永不触发，只有 delegate 侧一次
        assertThat(onRequest).hasValue(1);
        assertThat(onResponse).hasValue(1);
        // 自省代理：wrapper.listeners() 返回 delegate 的
        assertThat(model.listeners()).containsExactly(counting);
    }

    @Test
    void concurrentTenants_neverCrossAttribute() throws Exception {
        ThreadLocal<String> currentTenant = new ThreadLocal<>();
        CapturingChatModel shared = new CapturingChatModel(); // 响应体回显 user，按线程断言，不依赖共享捕获
        TenantAwareChatModel model = new TenantAwareChatModel(shared, TenantAttributionMode.USER, currentTenant::get);

        int tasks = 200;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch done = new CountDownLatch(tasks);
        ConcurrentLinkedQueue<String> mismatches = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < tasks; i++) {
            String tenant = "tenant-" + i;
            pool.submit(() -> {
                try {
                    currentTenant.set(tenant);
                    ChatResponse response = model.chat(
                            ChatRequest.builder().messages(UserMessage.from("hi")).build());
                    if (!tenant.equals(response.aiMessage().text())) {
                        mismatches.add(tenant + " got " + response.aiMessage().text());
                    }
                } finally {
                    currentTenant.remove();
                    done.countDown();
                }
            });
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(mismatches).isEmpty();
    }
}
