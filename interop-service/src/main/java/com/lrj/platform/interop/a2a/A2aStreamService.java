package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.agent.AgentTaskView;
import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A2A {@code message/stream}：把上游 SSE 翻成 A2A 流式事件序列，每个 SSE 帧体是包着事件的 JSON-RPC response：
 * <ol>
 *   <li>{@code status-update} WORKING（final=false）—— 开流</li>
 *   <li>逐 token/逐状态 {@code artifact-update} / {@code status-update} —— 增量产出</li>
 *   <li>{@code status-update} COMPLETED / FAILED（final=true）—— 收口</li>
 * </ol>
 *
 * <p>平台 interop 无本地 ChatModel，故按 skill 代理上游 SSE：
 * <ul>
 *   <li>chat → conversation {@code /chat/stream}（token 级 artifact 流，对齐单体 {@code A2aStreamService}）</li>
 *   <li>deep-research → agent {@code /agent/run/async} + {@code /agent/tasks/{id}/stream}（任务级状态流）</li>
 * </ul>
 * 上游消费是阻塞读，放到 {@code interopStreamExecutor}（含租户/MDC 透传）后台线程，控制器立刻返回 emitter。
 * 帧发送经 {@link A2aFrameSink} 抽象（生产为 {@link EmitterSink}），便于对翻译逻辑做确定性单测。
 */
@Service
public class A2aStreamService {

    private static final Logger log = LoggerFactory.getLogger(A2aStreamService.class);
    private static final long SSE_TIMEOUT_MS = 130_000L;

    private final StreamingConversationGateway conversationGateway;
    private final AgentTaskStreamGateway taskStreamGateway;
    private final A2aAgentGateway agentGateway;
    private final A2aTaskMapper mapper;
    private final ObjectMapper json;
    private final Executor executor;

    public A2aStreamService(StreamingConversationGateway conversationGateway,
                            AgentTaskStreamGateway taskStreamGateway,
                            A2aAgentGateway agentGateway,
                            A2aTaskMapper mapper,
                            ObjectMapper json,
                            @Qualifier("interopStreamExecutor") Executor executor) {
        this.conversationGateway = conversationGateway;
        this.taskStreamGateway = taskStreamGateway;
        this.agentGateway = agentGateway;
        this.mapper = mapper;
        this.json = json;
        this.executor = executor;
    }

    /** A2A 流式帧下沉抽象：send 一帧 A2A 事件，complete 收口流。 */
    interface A2aFrameSink {
        void send(Object event);

        void complete();
    }

    /** 生产实现：把事件包成 JSON-RPC response 序列化后写进 {@link SseEmitter}。 */
    static final class EmitterSink implements A2aFrameSink {
        private final SseEmitter emitter;
        private final Object rpcId;
        private final ObjectMapper json;

        EmitterSink(SseEmitter emitter, Object rpcId, ObjectMapper json) {
            this.emitter = emitter;
            this.rpcId = rpcId;
            this.json = json;
        }

        @Override
        public void send(Object event) {
            try {
                String payload = json.writeValueAsString(JsonRpcResponse.success(rpcId, event));
                emitter.send(SseEmitter.event().data(payload));
            } catch (IOException e) {
                emitter.completeWithError(e);
            } catch (Exception e) {
                log.warn("A2A stream serialize/send failed: {}", e.toString());
            }
        }

        @Override
        public void complete() {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 已 complete / 客户端断开：忽略
            }
        }
    }

    /** 开一个 A2A 流：按 skill 分派 chat / deep-research，后台线程消费上游 SSE。 */
    public SseEmitter stream(A2aMessage msg, String skill, Object rpcId) {
        if (msg == null || msg.textContent().isBlank()) {
            throw new IllegalArgumentException("message.parts must contain non-empty text");
        }
        String contextId = (msg.contextId() != null && !msg.contextId().isBlank())
                ? msg.contextId() : UUID.randomUUID().toString();
        String text = msg.textContent();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> { cancelled.set(true); emitter.complete(); });
        emitter.onError(e -> cancelled.set(true));
        A2aFrameSink sink = new EmitterSink(emitter, rpcId, json);

        // executor 带租户/MDC 透传：捕获当前请求线程的租户，供后台流式线程调下游时透传内部 JWT。
        if (A2aService.SKILL_RESEARCH.equals(skill)) {
            executor.execute(() -> runResearch(sink, cancelled, text, contextId));
        } else {
            executor.execute(() -> runChat(sink, cancelled, text, contextId));
        }
        return emitter;
    }

    // —— chat skill：conversation /chat/stream 的 token 级流 ——

    void runChat(A2aFrameSink sink, AtomicBoolean cancelled, String text, String contextId) {
        String taskId = UUID.randomUUID().toString();
        String artifactId = UUID.randomUUID().toString();
        String chatId = TenantContext.current().tenantId() + ":a2a:" + contextId;

        sink.send(new TaskStatusUpdateEvent(taskId, contextId,
                A2aTaskStatus.of(TaskState.WORKING, Instant.now().toString()), false));

        conversationGateway.streamChat(chatId, text,
                token -> {
                    if (cancelled.get() || token == null || token.isEmpty()) {
                        return;
                    }
                    sink.send(new TaskArtifactUpdateEvent(taskId, contextId,
                            Artifact.of(artifactId, "answer", token), true, false));
                },
                () -> {
                    if (!cancelled.get()) {
                        sink.send(new TaskStatusUpdateEvent(taskId, contextId,
                                A2aTaskStatus.of(TaskState.COMPLETED, Instant.now().toString()), true));
                    }
                    sink.complete();
                },
                err -> {
                    log.warn("A2A chat stream error task={}", taskId, err);
                    if (!cancelled.get()) {
                        sink.send(new TaskStatusUpdateEvent(taskId, contextId,
                                new A2aTaskStatus(TaskState.FAILED,
                                        A2aMessage.agentText(String.valueOf(err.getMessage()), taskId, contextId),
                                        Instant.now().toString()), true));
                    }
                    sink.complete();
                });
    }

    // —— deep-research skill：起异步任务 + agent /agent/tasks/{id}/stream 的任务级状态流 ——

    void runResearch(A2aFrameSink sink, AtomicBoolean cancelled, String text, String contextId) {
        AgentTaskView submitted;
        try {
            submitted = agentGateway.submitTask(text, null); // 流式：客户端看流，不登记 push webhook
        } catch (Exception e) {
            log.warn("A2A research stream submit failed", e);
            sink.send(new TaskStatusUpdateEvent(UUID.randomUUID().toString(), contextId,
                    new A2aTaskStatus(TaskState.FAILED,
                            A2aMessage.agentText("submit failed: " + e.getMessage(), null, contextId),
                            Instant.now().toString()), true));
            sink.complete();
            return;
        }
        if (submitted == null || submitted.taskId() == null) {
            sink.send(new TaskStatusUpdateEvent(UUID.randomUUID().toString(), contextId,
                    A2aTaskStatus.of(TaskState.FAILED, Instant.now().toString()), true));
            sink.complete();
            return;
        }
        String taskId = submitted.taskId();

        // 开流：先发一帧当前（submitted/working）状态。
        emitTaskStatus(sink, cancelled, taskId, contextId, submitted, false);

        taskStreamGateway.streamTask(taskId,
                view -> {
                    if (cancelled.get()) {
                        return;
                    }
                    boolean terminal = isTerminal(view.status());
                    if ("SUCCEEDED".equals(view.status())) {
                        String answer = mapper.renderResult(view.result());
                        if (answer != null && !answer.isBlank()) {
                            sink.send(new TaskArtifactUpdateEvent(taskId, contextId,
                                    Artifact.of(UUID.randomUUID().toString(), "answer", answer), false, true));
                        }
                    }
                    emitTaskStatus(sink, cancelled, taskId, contextId, view, terminal);
                },
                sink::complete,
                err -> {
                    log.warn("A2A research stream error task={}", taskId, err);
                    if (!cancelled.get()) {
                        sink.send(new TaskStatusUpdateEvent(taskId, contextId,
                                new A2aTaskStatus(TaskState.FAILED,
                                        A2aMessage.agentText(String.valueOf(err.getMessage()), taskId, contextId),
                                        Instant.now().toString()), true));
                    }
                    sink.complete();
                });
    }

    private void emitTaskStatus(A2aFrameSink sink, AtomicBoolean cancelled,
                                String taskId, String contextId, AgentTaskView view, boolean isFinal) {
        if (cancelled.get()) {
            return;
        }
        TaskState state = mapper.toTaskState(view.status());
        A2aMessage statusMsg = null;
        if ("FAILED".equals(view.status()) && view.error() != null && !view.error().isBlank()) {
            statusMsg = A2aMessage.agentText(view.error(), taskId, contextId);
        }
        String ts = firstNonBlank(view.updatedAt(), view.createdAt(), Instant.now().toString());
        sink.send(new TaskStatusUpdateEvent(taskId, contextId,
                new A2aTaskStatus(state, statusMsg, ts), isFinal));
    }

    private static boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return Instant.now().toString();
    }
}
