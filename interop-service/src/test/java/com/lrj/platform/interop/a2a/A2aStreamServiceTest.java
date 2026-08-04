package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.agent.AgentTaskView;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2aStreamService 翻译逻辑的确定性单测：直接驱动 {@code runChat}/{@code runResearch}，
 * 用捕获型 {@link A2aStreamService.A2aFrameSink} 断言产出的 A2A 帧序列（不起 Spring / 不连网络）。
 */
class A2aStreamServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final A2aTaskMapper mapper = new A2aTaskMapper();
    private final A2aPushNotificationStore stateStore = new A2aPushNotificationStore();

    /** 捕获型 sink：记录每一帧事件 + complete 次数。 */
    private static final class CapturingSink implements A2aStreamService.A2aFrameSink {
        final List<Object> events = new ArrayList<>();
        int completed = 0;

        @Override
        public void send(Object event) {
            events.add(event);
        }

        @Override
        public void complete() {
            completed++;
        }
    }

    private A2aStreamService service(StreamingConversationGateway conv,
                                     AgentTaskStreamGateway task,
                                     A2aAgentGateway agent) {
        return new A2aStreamService(conv, task, agent, stateStore, mapper, json, Runnable::run);
    }

    @Test
    void chatStreamEmitsWorkingThenTokenArtifactsThenCompleted() {
        StreamingConversationGateway conv = new StreamingConversationGateway() {
            @Override
            public void streamChat(String chatId, String message,
                                   StreamCancellation cancellation,
                                   Consumer<String> onToken, Runnable onDone, Consumer<Throwable> onError) {
                assertThat(chatId).isEqualTo("anonymous:a2a:ctx-1"); // 无租户 → ANONYMOUS
                assertThat(message).isEqualTo("hi");
                onToken.accept("Hello");
                onToken.accept(" world");
                onDone.run();
            }
        };
        CapturingSink sink = new CapturingSink();

        service(conv, noopTaskStream(), noopAgent()).runChat(sink, new StreamCancellation(), "hi", "ctx-1");

        assertThat(sink.events).hasSize(4);
        assertThat(sink.events.get(0)).isInstanceOf(TaskStatusUpdateEvent.class);
        TaskStatusUpdateEvent working = (TaskStatusUpdateEvent) sink.events.get(0);
        assertThat(working.status().state()).isEqualTo(TaskState.WORKING);
        assertThat(working.isFinal()).isFalse();

        TaskArtifactUpdateEvent a1 = (TaskArtifactUpdateEvent) sink.events.get(1);
        TaskArtifactUpdateEvent a2 = (TaskArtifactUpdateEvent) sink.events.get(2);
        assertThat(a1.append()).isTrue();
        assertThat(a1.artifact().parts().get(0).text()).isEqualTo("Hello");
        assertThat(a2.artifact().parts().get(0).text()).isEqualTo(" world");
        // 逐 token 追加到同一 artifactId
        assertThat(a1.artifact().artifactId()).isEqualTo(a2.artifact().artifactId());

        TaskStatusUpdateEvent done = (TaskStatusUpdateEvent) sink.events.get(3);
        assertThat(done.status().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(done.isFinal()).isTrue();
        assertThat(sink.completed).isEqualTo(1);
    }

    @Test
    void chatStreamUpstreamErrorEmitsFailedFinal() {
        StreamingConversationGateway conv = (chatId, message, cancellation, onToken, onDone, onError) ->
                onError.accept(new RuntimeException("provider-secret-must-not-leak"));
        CapturingSink sink = new CapturingSink();

        service(conv, noopTaskStream(), noopAgent()).runChat(sink, new StreamCancellation(), "hi", "ctx-1");

        Object last = sink.events.get(sink.events.size() - 1);
        assertThat(last).isInstanceOf(TaskStatusUpdateEvent.class);
        TaskStatusUpdateEvent failed = (TaskStatusUpdateEvent) last;
        assertThat(failed.status().state()).isEqualTo(TaskState.FAILED);
        assertThat(failed.status().message().textContent()).isEqualTo("conversation stream failed");
        assertThat(failed.status().message().textContent()).doesNotContain("provider-secret");
        assertThat(failed.isFinal()).isTrue();
        assertThat(sink.completed).isEqualTo(1);
    }

    @Test
    void chatStreamStopsForwardingWhenCancelled() {
        StreamCancellation cancelled = new StreamCancellation();
        cancelled.cancel(); // 客户端已断开
        StreamingConversationGateway conv = (chatId, message, cancellation, onToken, onDone, onError) -> {
            onToken.accept("x");
            onDone.run();
        };
        CapturingSink sink = new CapturingSink();

        service(conv, noopTaskStream(), noopAgent()).runChat(sink, cancelled, "hi", "ctx-1");

        // WORKING 开流帧在 cancelled 检查之前已发，但 token 与 COMPLETED 不再转发；emitter 仍 complete。
        assertThat(sink.events).noneMatch(e -> e instanceof TaskArtifactUpdateEvent);
        assertThat(sink.completed).isEqualTo(1);
    }

    @Test
    void researchStreamEmitsStatusSequenceAndFinalArtifact() {
        A2aAgentGateway agent = new StubAgentGateway(
                new AgentTaskView("t1", "acme", "alice", "PENDING", Map.of(), null, null, null, null, null));
        AgentTaskStreamGateway task = new AgentTaskStreamGateway() {
            @Override
            public void streamTask(String taskId, StreamCancellation cancellation,
                                   Consumer<AgentTaskView> onUpdate, Runnable onDone,
                                   Consumer<Throwable> onError) {
                assertThat(taskId).isEqualTo("t1");
                onUpdate.accept(view("t1", "RUNNING", null, null));
                onUpdate.accept(view("t1", "SUCCEEDED", Map.of("finalAnswer", "done"), null));
                onDone.run();
            }
        };
        CapturingSink sink = new CapturingSink();

        service(noopConv(), task, agent).runResearch(sink, new StreamCancellation(), "research", "ctx-2");

        // 初始 SUBMITTED、RUNNING→WORKING、SUCCEEDED 的 artifact + COMPLETED(final)
        assertThat(states(sink)).containsExactly(
                TaskState.SUBMITTED, TaskState.WORKING, TaskState.COMPLETED);
        TaskArtifactUpdateEvent artifact = sink.events.stream()
                .filter(e -> e instanceof TaskArtifactUpdateEvent)
                .map(e -> (TaskArtifactUpdateEvent) e).findFirst().orElseThrow();
        assertThat(artifact.artifact().parts().get(0).text()).isEqualTo("done");
        assertThat(artifact.lastChunk()).isTrue();
        assertThat(artifact.append()).isFalse();
        assertThat(sink.completed).isEqualTo(1);
        assertThat(stateStore.contextId("anonymous", "t1")).contains("ctx-2");

        // 终态帧 final=true
        TaskStatusUpdateEvent completed = sink.events.stream()
                .filter(e -> e instanceof TaskStatusUpdateEvent)
                .map(e -> (TaskStatusUpdateEvent) e)
                .filter(e -> e.status().state() == TaskState.COMPLETED).findFirst().orElseThrow();
        assertThat(completed.isFinal()).isTrue();
    }

    @Test
    void researchStreamSubmitFailureEmitsFailed() {
        A2aAgentGateway agent = new StubAgentGateway(null); // submit 返回 null
        CapturingSink sink = new CapturingSink();

        service(noopConv(), noopTaskStream(), agent).runResearch(sink, new StreamCancellation(), "x", "ctx-3");

        assertThat(states(sink)).containsExactly(TaskState.FAILED);
        assertThat(((TaskStatusUpdateEvent) sink.events.get(0)).isFinal()).isTrue();
        assertThat(sink.completed).isEqualTo(1);
    }

    @Test
    void researchStreamSanitizesTerminalError() {
        A2aAgentGateway agent = new StubAgentGateway(
                new AgentTaskView("t1", "acme", "alice", "PENDING", Map.of(), null, null, null, null, null));
        AgentTaskStreamGateway task = (taskId, cancellation, onUpdate, onDone, onError) -> {
            onUpdate.accept(view(taskId, "FAILED", null, "database-password=secret"));
            onDone.run();
        };
        CapturingSink sink = new CapturingSink();

        service(noopConv(), task, agent)
                .runResearch(sink, new StreamCancellation(), "research", "ctx-4");

        TaskStatusUpdateEvent failed = sink.events.stream()
                .filter(TaskStatusUpdateEvent.class::isInstance)
                .map(TaskStatusUpdateEvent.class::cast)
                .filter(event -> event.status().state() == TaskState.FAILED)
                .findFirst().orElseThrow();
        assertThat(failed.status().message().textContent()).isEqualTo("agent task failed");
        assertThat(failed.status().message().textContent()).doesNotContain("database-password");
    }

    @Test
    void researchDisconnectClosesStreamAndCancelsSubmittedTask() {
        StubAgentGateway agent = new StubAgentGateway(
                new AgentTaskView("t1", "acme", "alice", "PENDING", Map.of(), null, null, null, null, null));
        AgentTaskStreamGateway task = (taskId, cancellation, onUpdate, onDone, onError) ->
                cancellation.cancel();
        CapturingSink sink = new CapturingSink();

        service(noopConv(), task, agent)
                .runResearch(sink, new StreamCancellation(), "research", "ctx-5");

        assertThat(agent.cancelledTask).isEqualTo("t1");
        assertThat(sink.completed).isEqualTo(1);
    }

    @Test
    void executorRejectionReturnsAClosedStreamInsteadOfLeakingException() {
        A2aStreamService service = new A2aStreamService(
                noopConv(), noopTaskStream(), noopAgent(), stateStore, mapper, json,
                task -> {
                    throw new IllegalStateException("executor-secret=must-not-leak");
                });
        A2aMessage message = new A2aMessage(
                "user", List.of(Part.text("hello")), "message-1", null, "context-1", null);

        SseEmitter emitter = service.stream(message, A2aService.SKILL_CHAT, "rpc-1");

        assertThat(emitter).isNotNull();
    }

    // —— helpers ——

    private static List<TaskState> states(CapturingSink sink) {
        List<TaskState> out = new ArrayList<>();
        for (Object e : sink.events) {
            if (e instanceof TaskStatusUpdateEvent s) {
                out.add(s.status().state());
            }
        }
        return out;
    }

    private static AgentTaskView view(String id, String status, Object result, String error) {
        return new AgentTaskView(id, "acme", "alice", status, Map.of(), result, error,
                "2026-07-08T00:00:00Z", "2026-07-08T00:00:01Z", null);
    }

    private StreamingConversationGateway noopConv() {
        return (chatId, message, cancellation, onToken, onDone, onError) -> onDone.run();
    }

    private AgentTaskStreamGateway noopTaskStream() {
        return (taskId, cancellation, onUpdate, onDone, onError) -> onDone.run();
    }

    private A2aAgentGateway noopAgent() {
        return new StubAgentGateway(null);
    }

    private static final class StubAgentGateway implements A2aAgentGateway {
        private final AgentTaskView submitResult;
        private String cancelledTask;

        StubAgentGateway(AgentTaskView submitResult) {
            this.submitResult = submitResult;
        }

        @Override
        public String chat(String text) {
            return "";
        }

        @Override
        public AgentTaskView submitTask(String goal, String webhookUrl) {
            return submitResult;
        }

        @Override
        public Optional<AgentTaskView> getTask(String taskId) {
            return Optional.empty();
        }

        @Override
        public boolean cancelTask(String taskId) {
            cancelledTask = taskId;
            return true;
        }
    }
}
