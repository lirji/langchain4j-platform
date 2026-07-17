package com.lrj.platform.agent.async;

import com.lrj.platform.protocol.agent.AgentRunRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgentTaskControllerTest：验证 {@link AgentTaskController} 异步提交返回 202、空 goal 返回 400、
 * 查询缺失任务返回 404，以及 SSE 订阅存在任务时返回 emitter。
 */
class AgentTaskControllerTest {

    @Test
    void runAsyncReturnsAcceptedTask() {
        AgentAsyncTaskService tasks = mock(AgentAsyncTaskService.class);
        AgentTaskController controller = new AgentTaskController(tasks, mock(AgentTaskSseService.class));
        AgentAsyncTask task = task("task-1", AgentTaskStatus.PENDING);
        when(tasks.submit("goal", "http://callback.local/tasks")).thenReturn(task);

        var response = controller.runAsync(new AgentRunRequest("goal", "http://callback.local/tasks"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isEqualTo(task);
        verify(tasks).submit("goal", "http://callback.local/tasks");
    }

    @Test
    void runAsyncRejectsBlankGoal() {
        AgentTaskController controller = new AgentTaskController(
                mock(AgentAsyncTaskService.class),
                mock(AgentTaskSseService.class));

        var response = controller.runAsync(new AgentRunRequest(" "));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void getMissingTaskReturnsNotFound() {
        AgentAsyncTaskService tasks = mock(AgentAsyncTaskService.class);
        AgentTaskController controller = new AgentTaskController(tasks, mock(AgentTaskSseService.class));
        when(tasks.get("missing")).thenReturn(Optional.empty());

        var response = controller.get("missing");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void streamReturnsEmitterWhenTaskExists() {
        AgentTaskSseService sse = mock(AgentTaskSseService.class);
        AgentTaskController controller = new AgentTaskController(mock(AgentAsyncTaskService.class), sse);
        SseEmitter emitter = new SseEmitter();
        when(sse.subscribe("task-1")).thenReturn(Optional.of(emitter));

        var response = controller.stream("task-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(emitter);
    }

    private static AgentAsyncTask task(String taskId, AgentTaskStatus status) {
        Instant now = Instant.now();
        return new AgentAsyncTask(taskId, "acme", "alice", status, Map.of("goal", "goal"),
                null, null, now, now, null);
    }
}
