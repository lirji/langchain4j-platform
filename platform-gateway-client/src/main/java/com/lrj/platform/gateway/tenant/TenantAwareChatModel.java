package com.lrj.platform.gateway.tenant;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

import java.util.List;
import java.util.Set;

/**
 * 每请求把可信 tenantId 写进 OpenAI {@code user} 字段的 {@link ChatModel} 包装器 —— LiteLLM 侧
 * spend 按 end-user（=租户）归集的前提。无状态、线程安全；tenantId 每次调用从
 * {@link TenantIdentityProvider}（底层是 TenantContext ThreadLocal）现取，调用方传入的 {@code user}
 * 一律被覆盖（防伪造）。
 *
 * <p><strong>为何只覆盖两参 {@code chat}：</strong>langchain4j 1.13 里所有便捷重载
 * （{@code chat(String)} / {@code chat(ChatMessage...)} / {@code chat(ChatRequest)}）都汇入
 * {@code chat(ChatRequest, ChatRequestOptions)}，且 listener 边界（onRequest → doChat → onResponse）
 * 就在该 default 方法内。本类覆盖它并直接委托 delegate 的同名重载 —— listener 只在 delegate 侧
 * 执行一次；本类的 default 路径永不触发，不会重复回调。（经 1.13.1 字节码核验。）
 *
 * <p>参数合并顺序 {@code defaultRequestParameters().overrideWith(request.parameters())} 保证
 * 请求级 {@code user} 胜过 delegate 默认值。不记录 messages 与任何 secret。
 */
public final class TenantAwareChatModel implements ChatModel {

    private final ChatModel delegate;
    private final TenantAttributionMode mode;
    private final TenantIdentityProvider identities;

    public TenantAwareChatModel(ChatModel delegate, TenantAttributionMode mode, TenantIdentityProvider identities) {
        this.delegate = delegate;
        this.mode = mode;
        this.identities = identities;
    }

    @Override
    public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        if (mode == TenantAttributionMode.NONE) {
            return delegate.chat(request, options);
        }
        return delegate.chat(attributedRequest(request, identities.currentTenantId()), options);
    }

    /**
     * 复制请求并把 {@code user} 强制为可信 tenantId；其余参数逐字保留。package-private 供流式
     * sibling 复用与单测直验。
     */
    static ChatRequest attributedRequest(ChatRequest request, String tenantId) {
        OpenAiChatRequestParameters parameters = OpenAiChatRequestParameters.builder()
                .overrideWith(request.parameters())
                .user(tenantId)
                .build();
        return request.toBuilder().parameters(parameters).build();
    }

    // ── 以下纯代理，保持接口自省语义（实际执行边界已直接进入 delegate）──

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }
}
