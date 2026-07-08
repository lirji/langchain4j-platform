package com.lrj.platform.conversation;

import com.lrj.platform.conversation.grounding.GroundingChecker;
import com.lrj.platform.conversation.grounding.GroundingResult;
import com.lrj.platform.conversation.guardrail.ConversationGuardrail;
import com.lrj.platform.conversation.history.HistoryAwareQueryCompressor;
import com.lrj.platform.conversation.prompt.ResolvedAssistantStyle;
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
    private final HistoryAwareQueryCompressor historyCompressor;
    private final GroundingChecker groundingChecker;
    private final ResolvedAssistantStyle style;

    public StreamingConversationController(StreamingAssistant streamingAssistant,
                                           RagPromptAugmenter ragPromptAugmenter,
                                           ConversationGuardrail guardrail,
                                           HistoryAwareQueryCompressor historyCompressor,
                                           GroundingChecker groundingChecker,
                                           ResolvedAssistantStyle style) {
        this.streamingAssistant = streamingAssistant;
        this.ragPromptAugmenter = ragPromptAugmenter;
        this.guardrail = guardrail;
        this.historyCompressor = historyCompressor;
        this.groundingChecker = groundingChecker;
        this.style = style;
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
        // per-request 类目（可空）：与 /chat 对称，非空时把检索限定到该 metadata.category 的文档。
        String category = body.get("category");
        String memoryKey = tenant.tenantId() + "::" + chatId;
        // History-aware：追问经会话历史压缩为自包含检索 query（默认关时直通）；仅用于检索。
        String retrievalQuery = historyCompressor.compress(memoryKey, effective);
        RagPromptAugmenter.RagContext rag = ragPromptAugmenter.contextWithHits(retrievalQuery, category);

        // 累积逐 token 答案，结束时对 RAG 来源做 grounding 校验；token 已逐个发出无法回收，
        // 故 warn 以追加式 grounding-warning 事件补发（对齐单体）。默认关时 grounded → 不发。
        StringBuilder answer = new StringBuilder();
        TokenStream stream = streamingAssistant.chat(memoryKey, style.getLanguage(), style.getTone(),
                style.getCitationPolicy(), style.getExtra(), effective, rag.context());
        stream.onPartialResponse(token -> {
                    if (token != null) {
                        answer.append(token);
                    }
                    safeSend(emitter, token);
                })
                .onCompleteResponse(response -> completeWithGrounding(emitter, answer.toString(), rag))
                .onError(error -> fail(emitter, error))
                .start();
        return emitter;
    }

    private void completeWithGrounding(SseEmitter emitter, String answer, RagPromptAugmenter.RagContext rag) {
        GroundingResult grounded = groundingChecker.verify(answer, rag.hits());
        if (!grounded.grounded()) {
            try {
                emitter.send(SseEmitter.event().name("grounding-warning")
                        .data(String.join("；", grounded.warnings())));
            } catch (IOException | IllegalStateException ignored) {
                // 客户端可能已断开，忽略
            }
        }
        complete(emitter);
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
