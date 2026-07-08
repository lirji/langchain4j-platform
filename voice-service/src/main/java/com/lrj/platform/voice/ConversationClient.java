package com.lrj.platform.voice;

import java.util.function.Consumer;

/**
 * 语音服务的「大脑」抽象：把转写文本送对话服务，取回复文本。
 * 单体里是同进程 {@code CustomerServiceBrain}；v2 微服务拆分后经 HTTP 调 conversation-service {@code /chat}。
 */
public interface ConversationClient {

    /**
     * @param chatId  会话 id（隔离多轮记忆，同 conversation {@code /chat}）
     * @param message 用户消息（转写文本）
     * @return 助手回复文本（可能含 {@code [doc=...]} 引用标记，TTS 前需剥离）
     */
    String chat(String chatId, String message);

    /**
     * 真 token 流式：消费 conversation {@code /chat/stream} 的 SSE，逐 token 回调，供语音「凑句即 TTS」用。
     * 恰调用一次 {@code onDone}（正常收口）或 {@code onError}（失败），阻塞直到流结束。
     *
     * <p>默认实现降级为一次 unary {@link #chat}：整段回复作单个 token 吐出后 {@code done}——无真流式实现
     * （或测试桩）时行为等价于原半流式。{@link HttpConversationClient} 覆盖为真 SSE 消费。
     */
    default void chatStream(String chatId, String message,
                            Consumer<String> onToken, Runnable onDone, Consumer<Throwable> onError) {
        try {
            String reply = chat(chatId, message);
            if (reply != null && !reply.isEmpty()) {
                onToken.accept(reply);
            }
            onDone.run();
        } catch (RuntimeException e) {
            onError.accept(e);
        }
    }
}
