package com.lrj.platform.agent.reflexion;

import com.lrj.platform.agent.async.AgentTaskProgressSink;
import com.lrj.platform.protocol.agent.ReflexionRequest;
import com.lrj.platform.security.PublicPayloadRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reflexion 自省环入口（{@code DeepAgentService} 的同级 sibling 编排器）。走 {@code /agent/**} 同套
 * 鉴权链（内部 JWT + 多租户 + 限流 + 配额），与单体 {@code /chat/reflexive[/stream]} 行为对齐、端点迁到
 * {@code /agent/reflexive[/stream]}。
 *
 * <ul>
 *   <li>{@code POST /agent/reflexive} → 同步跑完自省环，返回 {@code ReflexionReply}（含各轮评分轨迹）。</li>
 *   <li>{@code POST /agent/reflexive/stream} → SSE，分阶段推送 {@code attempt-start / answer / critique / done}
 *       事件（复用 {@code agentTaskExecutor} 后台跑 + {@code AgentTaskProgressSink} 桥接到 {@code SseEmitter}）。</li>
 * </ul>
 * 客户端断开后会在模型调用之间协作式停止；langchain4j 同步模型接口不提供当前调用的取消句柄，
 * 所以已进入 provider 的单次调用只能等待其自身超时，期间不会继续向已关闭的下游写事件。
 */
@RestController
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class ReflexionController {

    private static final Logger log = LoggerFactory.getLogger(ReflexionController.class);
    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L;

    private final ReflexionService reflexion;
    private final Executor executor;
    private final PublicPayloadRedactor redactor;

    public ReflexionController(ReflexionService reflexion,
                               @Qualifier("agentTaskExecutor") Executor executor,
                               PublicPayloadRedactor redactor) {
        this.reflexion = reflexion;
        this.executor = executor;
        this.redactor = redactor;
    }

    @PostMapping("/agent/reflexive")
    public ResponseEntity<?> reflexive(@RequestBody ReflexionRequest request) {
        String question = request == null || request.question() == null ? "" : request.question();
        if (question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }
        return ResponseEntity.ok(reflexion.reflect(question));
    }

    @PostMapping(value = "/agent/reflexive/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter reflexiveStream(@RequestBody ReflexionRequest request) {
        String question = request == null || request.question() == null ? "" : request.question();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        if (question.isBlank()) {
            safeSend(emitter, "error", Map.of("error", "question is required"));
            emitter.complete();
            return emitter;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        emitter.onCompletion(() -> {
            if (!finished.get()) {
                cancelled.set(true);
            }
        });
        emitter.onTimeout(() -> {
            cancelled.set(true);
            emitter.complete();
        });
        emitter.onError(error -> cancelled.set(true));

        // agentTaskExecutor 的 TaskDecorator 已透传 TenantContext / MDC，后台线程里租户上下文可用。
        try {
            executor.execute(() -> {
                try {
                    reflexion.reflect(question, sink(emitter, cancelled, redactor));
                    if (!cancelled.get()) {
                        finished.set(true);
                        emitter.complete();
                    }
                } catch (CancellationException ignored) {
                    cancelled.set(true);
                    log.info("reflexive stream cancelled; active model call cancellation is unsupported");
                } catch (Exception error) {
                    if (!cancelled.get()) {
                        finished.set(true);
                        fail(emitter, error);
                    }
                }
            });
        } catch (RuntimeException error) {
            finished.set(true);
            fail(emitter, error);
        }
        return emitter;
    }

    static AgentTaskProgressSink sink(SseEmitter emitter, AtomicBoolean cancelled,
                                      PublicPayloadRedactor redactor) {
        return new AgentTaskProgressSink() {
            @Override
            public void emit(String event, Object data) {
                throwIfCancelled();
                if (!safeSend(emitter, event, redactor.redact(data))) {
                    cancelled.set(true);
                    throw new CancellationException("reflexive stream disconnected");
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
    }

    static void fail(SseEmitter emitter, Throwable error) {
        log.warn("reflexive stream failed errorType={}", error.getClass().getSimpleName());
        safeSend(emitter, "error", Map.of(
                "error", "agent reflexion failed",
                "code", "AGENT_REFLEXION_STREAM_FAILED"));
        emitter.complete();
    }

    private static boolean safeSend(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            return true;
        } catch (IOException | IllegalStateException ignored) {
            return false;
        }
    }
}
