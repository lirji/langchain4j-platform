package com.lrj.platform.conversation;

import com.lrj.platform.conversation.guardrail.ConversationGuardrail;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * {@code POST /chat/stream}：token 级 SSE 流式对话（对齐单体 {@code ChatController#chatStream}）。
 * 逐 token 以默认 {@code data:} 事件下发，结束发 {@code event: done}，出错发 {@code event: error} 并关闭。
 *
 * <p>记忆键与同步 {@code /chat} 一致（{@code <tenantId>::<chatId>}），RAG 来源经 {@code contextFor} 注入；
 * 流式不挂语义缓存。单独一个 controller，避免改动 {@link ConversationController} 的构造签名与既有测试。
 */
@RestController
public class StreamingConversationController {

    private static final Logger log = LoggerFactory.getLogger(StreamingConversationController.class);
    private static final long SSE_TIMEOUT_MS = 120_000L;

    private final StreamingAssistant streamingAssistant;
    private final RagPromptAugmenter ragPromptAugmenter;
    private final ConversationGuardrail guardrail;

    public StreamingConversationController(StreamingAssistant streamingAssistant,
                                           RagPromptAugmenter ragPromptAugmenter,
                                           ConversationGuardrail guardrail) {
        this.streamingAssistant = streamingAssistant;
        this.ragPromptAugmenter = ragPromptAugmenter;
        this.guardrail = guardrail;
    }

    @PostMapping("/chat/stream")
    public SseEmitter chatStream(@RequestParam(value = "chatId", defaultValue = "default") String chatId,
                                 @RequestBody Map<String, String> body) {
        TenantContext.Tenant tenant = TenantContext.current();
        String message = body.getOrDefault("message", "");
        // 前置注入护栏：block 档命中即发一条 blocked 事件收尾，不进 LLM。（输出 PII 脱敏不挂流式：token 已逐个发出无法回收，对齐单体）
        ConversationGuardrail.InputDecision decision = guardrail.inspectInput(message);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        if (decision.blocked()) {
            try {
                emitter.send(SseEmitter.event().name("blocked").data(decision.blockReply()));
                emitter.send(SseEmitter.event().name("done").data(""));
            } catch (IOException | IllegalStateException ignored) {
                // 客户端已断开则直接收尾
            }
            emitter.complete();
            return emitter;
        }
        String effective = decision.message();
        String memoryKey = tenant.tenantId() + "::" + chatId;
        String context = ragPromptAugmenter.contextFor(effective);

        TokenStream stream = streamingAssistant.chat(memoryKey, effective, context);
        stream.onPartialResponse(token -> safeSend(emitter, token))
                .onCompleteResponse(response -> complete(emitter))
                .onError(error -> fail(emitter, error))
                .start();
        return emitter;
    }

    private static void safeSend(SseEmitter emitter, String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(token));
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开：终止流，避免继续向已关闭连接写。
            emitter.completeWithError(e);
        }
    }

    private static void complete(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("done").data(""));
        } catch (IOException | IllegalStateException ignored) {
            // 客户端可能已断开，忽略收尾发送失败
        }
        emitter.complete();
    }

    private static void fail(SseEmitter emitter, Throwable error) {
        log.warn("chat stream error: {}", error.toString());
        try {
            emitter.send(SseEmitter.event().name("error").data(String.valueOf(error.getMessage())));
        } catch (IOException | IllegalStateException ignored) {
            // 已断开则直接以错误收尾
        }
        emitter.completeWithError(error);
    }
}
