package com.lrj.platform.conversation;

import com.lrj.platform.conversation.grounding.GroundingChecker;
import com.lrj.platform.conversation.grounding.GroundingResult;
import com.lrj.platform.conversation.guardrail.ConversationGuardrail;
import com.lrj.platform.conversation.guardrail.StreamingPiiRedactor;
import com.lrj.platform.conversation.history.HistoryAwareQueryCompressor;
import com.lrj.platform.conversation.prompt.ResolvedAssistantStyle;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.model.chat.response.StreamingHandle;
import com.lrj.platform.conversation.memory.ConversationHistoryReader;
import com.lrj.platform.conversation.shadow.ConversationStreamShadowObserver;
import com.lrj.platform.protocol.conversation.ConversationGenerationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private static final int MAX_STREAM_CHARS = 65_536;
    private final ConversationHistoryReader historyReader;
    private final ConversationStreamShadowObserver shadow;
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
        this(streamingAssistant, ragPromptAugmenter, guardrail, historyCompressor, groundingChecker,
                style, (tenant, chat) -> java.util.List.of(), ConversationStreamShadowObserver.disabled());
    }

    @Autowired
    public StreamingConversationController(StreamingAssistant streamingAssistant, RagPromptAugmenter ragPromptAugmenter,
            ConversationGuardrail guardrail, HistoryAwareQueryCompressor historyCompressor,
            GroundingChecker groundingChecker, ResolvedAssistantStyle style,
            ConversationHistoryReader historyReader, ConversationStreamShadowObserver shadow) {
        this.historyReader = historyReader;
        this.shadow = shadow;
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
        // 前置注入护栏：block 档命中即发一条 blocked 事件收尾，不进 LLM。
        ConversationGuardrail.InputDecision decision = guardrail.inspectInput(message);
        SseEmitter emitter = createEmitter();
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
        StreamingPiiRedactor pii = new StreamingPiiRedactor(guardrail);
        StreamControl control = new StreamControl();
        emitter.onCompletion(() -> control.clientClosed("completion"));
        emitter.onTimeout(() -> {
            control.transportFailed("timeout");
            emitter.complete();
        });
        emitter.onError(ignored -> control.transportFailed("transport_error"));
        try {
            if (shadow.enabled()) {
                var request = new ConversationGenerationRequest("1", effective, rag.context(),
                        new ConversationGenerationRequest.Style(style.getLanguage(), style.getTone(),
                                style.getCitationPolicy(), style.getExtra()),
                        historyReader.snapshot(tenant.tenantId(), chatId));
                control.prepareShadow(() -> shadow.open(request));
            }
        } catch (RuntimeException ignored) {
            log.warn("conversation stream shadow snapshot/submission failed");
        }
        try {
            TokenStream stream = streamingAssistant.chat(memoryKey, style.getLanguage(), style.getTone(),
                    style.getCitationPolicy(), style.getExtra(), effective, rag.context());
            stream.onPartialResponseWithContext((partial, context) -> {
                    control.attach(context.streamingHandle());
                    control.callback(() -> {
                        if (!control.running()) return;
                        String token = partial.text();
                        if (token != null) {
                            if (answer.length() + token.length() > MAX_STREAM_CHARS) {
                                fail(emitter, new IllegalStateException("stream size exceeded"), control);
                                control.cancelUpstream();
                                return;
                            }
                            answer.append(token);
                        }
                        safeSend(emitter, pii.accept(token), control);
                    });
                })
                    .onPartialThinkingWithContext((partial, context) -> control.attach(context.streamingHandle()))
                    .onPartialToolCallWithContext((partial, context) -> control.attach(context.streamingHandle()))
                    .onCompleteResponse(response -> control.callback(() ->
                            completeWithGrounding(emitter, answer.toString(), rag, pii, control)))
                    .onError(error -> control.callback(() -> fail(emitter, error, control)))
                    .start();
        } catch (RuntimeException error) {
            fail(emitter, error, control);
        }
        return emitter;
    }

    private void completeWithGrounding(SseEmitter emitter, String answer,
                                       RagPromptAugmenter.RagContext rag, StreamingPiiRedactor pii,
                                       StreamControl control) {
        if (!control.running()) return;
        try {
            safeSend(emitter, pii.finish(), control);
            if (control.closed()) return;
            GroundingResult grounded = groundingChecker.verify(answer, rag.hits());
            if (!grounded.grounded()) {
                emitter.send(SseEmitter.event().name("grounding-warning")
                        .data(guardrail.redactOutput(String.join("；", grounded.warnings()))));
            }
            if (control.closed() || !control.tryTerminal()) return;
            // 主模型与grounding均完成后才预留候选预算, 不抢占本次主请求的后续模型额度.
            control.beginShadow();
            try { control.shadow.finish(answer); } catch (RuntimeException ignored) {
                log.warn("conversation stream shadow completion failed");
            }
            complete(emitter, control);
        } catch (IOException error) {
            control.transportFailed("write_failed");
            emitter.complete();
        } catch (RuntimeException error) {
            fail(emitter, error, control);
        }
    }

    private static void safeSend(SseEmitter emitter, String token, StreamControl control) {
        if (control.closed() || token == null || token.isEmpty()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(token));
        } catch (IOException | IllegalStateException e) {
            // 停止下游写并取消已取得的上游句柄; 首帧前断连则在句柄迟到时取消.
            control.transportFailed("write_failed");
            emitter.complete();
        }
    }

    private static void complete(SseEmitter emitter, StreamControl control) {
        try {
            emitter.send(SseEmitter.event().name("done").data(""));
        } catch (IOException | IllegalStateException ignored) {
            control.transportFailed("terminal_write_failed");
        }
        emitter.complete();
    }

    static void fail(SseEmitter emitter, Throwable error) {
        fail(emitter, error, new StreamControl());
    }

    private static void fail(SseEmitter emitter, Throwable error, StreamControl control) {
        log.warn("chat stream failed errorType={}", error.getClass().getSimpleName());
        if (control.closed() || !control.tryTerminal()) return;
        try { control.shadow.cancel(); } catch (RuntimeException ignored) {
            log.warn("conversation stream shadow cancellation failed");
        }
        control.cancelUpstream();
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of(
                    "error", "conversation stream failed",
                    "code", "CONVERSATION_STREAM_FAILED")));
        } catch (IOException | IllegalStateException ignored) {
            // 已断开则直接以错误收尾
        }
        emitter.complete();
    }

    /** 单测可替换emitter验证发送失败; 生产仍使用原SseEmitter协议. */
    protected SseEmitter createEmitter() { return new SseEmitter(SSE_TIMEOUT_MS); }

    static final class StreamControl {
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private final TenantContext.Tenant tenant = TenantContext.captureRaw();
        private final Map<String, String> mdc = MDC.getCopyOfContextMap();
        private final Object handleLock = new Object();
        private StreamingHandle handle;
        private StreamingHandle cancelledHandle;
        private java.util.function.Supplier<ConversationStreamShadowObserver.Observation> shadowFactory;
        private volatile ConversationStreamShadowObserver.Observation shadow = ConversationStreamShadowObserver.noop();

        boolean closed() { return closed.get(); }
        boolean running() { return !closed.get() && !terminal.get(); }
        boolean tryTerminal() { return terminal.compareAndSet(false, true); }

        /** SDK回调没有servlet ThreadLocal, 显式恢复grounding/预算所需身份并在回调后清理. */
        synchronized void callback(Runnable action) {
            var previous = TenantContext.captureRaw();
            var previousMdc = MDC.getCopyOfContextMap();
            try {
                if (tenant == null) TenantContext.clear(); else TenantContext.set(tenant);
                if (mdc == null) MDC.clear(); else MDC.setContextMap(mdc);
                action.run();
            } finally {
                if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
                if (previousMdc == null) MDC.clear(); else MDC.setContextMap(previousMdc);
            }
        }

        void prepareShadow(java.util.function.Supplier<ConversationStreamShadowObserver.Observation> factory) {
            shadowFactory = factory;
        }

        void beginShadow() {
            var factory = shadowFactory;
            shadowFactory = null;
            if (factory == null || closed.get()) return;
            try {
                shadow = factory.get();
                if (closed.get()) shadow.cancel();
            } catch (RuntimeException ignored) {
                log.warn("conversation stream shadow submission failed");
            }
        }

        void attach(StreamingHandle current) {
            synchronized (handleLock) {
                handle = current;
                if (cancelRequested.get()) cancelUpstream();
            }
        }

        void cancelUpstream() {
            cancelRequested.set(true);
            synchronized (handleLock) {
                if (handle != null && handle != cancelledHandle) {
                    cancelledHandle = handle;
                    try { handle.cancel(); } catch (RuntimeException ignored) {
                        log.warn("chat stream upstream cancellation failed");
                    }
                }
            }
        }

        void clientClosed(String reason) {
            if (!terminal.get()) transportFailed(reason);
        }

        void transportFailed(String reason) {
            if (closed.compareAndSet(false, true)) {
                cancelUpstream();
                try { shadow.cancel(); } catch (RuntimeException ignored) {
                    log.warn("conversation stream shadow cancellation failed");
                }
                log.info("chat stream downstream closed reason={} cancellationRequested=true", reason);
            }
        }
    }
}
