package com.lrj.platform.gateway.tenant;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.List;
import java.util.Set;

/**
 * {@link TenantAwareChatModel} 的流式 sibling：每请求把可信 tenantId 写进 {@code user} 后再委托。
 * 归因发生在发起请求的调用线程（TenantContext 可取），与 SSE 回调线程无关。
 *
 * <p>覆盖三参 {@code chat} 即全覆盖：两参 default 汇入三参（EMPTY options），listener 边界也在
 * 三参 default 内 —— 直接委托 delegate 的三参重载，listener 只在 delegate 侧执行一次。
 * （与同步侧相同的字节码核验结论。）
 */
public final class TenantAwareStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;
    private final TenantAttributionMode mode;
    private final TenantIdentityProvider identities;

    public TenantAwareStreamingChatModel(StreamingChatModel delegate, TenantAttributionMode mode,
                                         TenantIdentityProvider identities) {
        this.delegate = delegate;
        this.mode = mode;
        this.identities = identities;
    }

    @Override
    public void chat(ChatRequest request, ChatRequestOptions options, StreamingChatResponseHandler handler) {
        if (mode == TenantAttributionMode.NONE) {
            delegate.chat(request, options, handler);
            return;
        }
        delegate.chat(TenantAwareChatModel.attributedRequest(request, identities.currentTenantId()),
                options, handler);
    }

    // ── 纯代理，保持接口自省语义 ──

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
