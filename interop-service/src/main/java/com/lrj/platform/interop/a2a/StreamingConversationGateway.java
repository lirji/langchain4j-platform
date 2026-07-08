package com.lrj.platform.interop.a2a;

import java.util.function.Consumer;

/**
 * interop → conversation-service 的 token 流式代理网关：消费 conversation {@code POST /chat/stream}
 * 的 SSE，逐 token 回调。用于把 A2A {@code message/stream}（chat skill）翻成 token 级 artifact 流。
 *
 * <p>实现类挂租户/trace forwarder（内部 JWT 透传）。回调在消费线程上同步触发，消费结束后调用
 * {@code onDone}（正常）或 {@code onError}（异常/上游 error 事件）。
 */
public interface StreamingConversationGateway {

    void streamChat(String chatId, String message,
                    Consumer<String> onToken, Runnable onDone, Consumer<Throwable> onError);
}
