package com.lrj.platform.agent.reflexion;

import com.lrj.platform.agent.async.AgentTaskProgressSink;
import com.lrj.platform.protocol.agent.ReflexionRequest;
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
import java.util.concurrent.Executor;

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
 */
@RestController
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class ReflexionController {

    private static final Logger log = LoggerFactory.getLogger(ReflexionController.class);
    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L;

    private final ReflexionService reflexion;
    private final Executor executor;

    public ReflexionController(ReflexionService reflexion,
                               @Qualifier("agentTaskExecutor") Executor executor) {
        this.reflexion = reflexion;
        this.executor = executor;
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
        // agentTaskExecutor 的 TaskDecorator 已透传 TenantContext / MDC，后台线程里租户上下文可用。
        executor.execute(() -> {
            try {
                reflexion.reflect(question, sink(emitter));
                emitter.complete();
            } catch (Exception e) {
                log.error("reflexive stream failed", e);
                safeSend(emitter, "error", Map.of("error", String.valueOf(e.getMessage())));
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private static AgentTaskProgressSink sink(SseEmitter emitter) {
        return (event, data) -> safeSend(emitter, event, data);
    }

    private static void safeSend(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException ignored) {
            // emitter 可能已关闭，由外层 try/catch 兜底
        }
    }
}
