package com.lrj.platform.agent.dag;

import com.lrj.platform.agent.async.AgentAsyncTaskService;
import com.lrj.platform.agent.async.AgentAsyncTask;
import com.lrj.platform.agent.async.AgentTaskStatus;
import com.lrj.platform.protocol.agent.AgentDagRunRequest;
import com.lrj.platform.protocol.agent.AgentDagTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgentDagControllerTest：验证 {@link AgentDagController} 的入参校验与状态码——空 goal 返回 400、
 * 非法 DAG（成环）返回 400 并回带错误信息，以及异步接口返回 202 与被接受的任务。
 */
class AgentDagControllerTest {

    @Test
    void blankGoalReturnsBadRequest() {
        AgentDagController controller = new AgentDagController(
                mock(AgentDagService.class),
                mock(AgentAsyncTaskService.class));

        var response = controller.run(new AgentDagRunRequest(" ", List.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void illegalDagReturnsBadRequest() {
        AgentDagService service = mock(AgentDagService.class);
        AgentDagController controller = new AgentDagController(service, mock(AgentAsyncTaskService.class));
        AgentDagRunRequest request = new AgentDagRunRequest("goal",
                List.of(new AgentDagTask("t1", "a", List.of("t1"))));
        when(service.run(request)).thenThrow(new IllegalArgumentException("task graph contains a cycle"));

        var response = controller.run(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).hasFieldOrPropertyWithValue("error", "task graph contains a cycle");
    }

    @Test
    void planAndRunRejectsBlankGoal() {
        AgentDagController controller = new AgentDagController(
                mock(AgentDagService.class),
                mock(AgentAsyncTaskService.class));

        var response = controller.planAndRun(java.util.Map.of("goal", " "));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void asyncDagRejectsBlankGoal() {
        AgentDagController controller = new AgentDagController(
                mock(AgentDagService.class),
                mock(AgentAsyncTaskService.class));

        var response = controller.runAsync(new AgentDagRunRequest(" ", List.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void asyncDagReturnsAcceptedTask() {
        AgentDagService dag = mock(AgentDagService.class);
        AgentAsyncTaskService tasks = mock(AgentAsyncTaskService.class);
        AgentDagController controller = new AgentDagController(dag, tasks);
        AgentDagRunRequest request = new AgentDagRunRequest("goal",
                List.of(new AgentDagTask("t1", "work", List.of())));
        AgentAsyncTask task = task("task-1");
        when(tasks.submitWithProgress(
                org.mockito.ArgumentMatchers.eq("AGENT_DAG"),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.any())).thenReturn(task);

        var response = controller.runAsync(request);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isEqualTo(task);
    }

    private static AgentAsyncTask task(String taskId) {
        Instant now = Instant.now();
        return new AgentAsyncTask(taskId, "acme", "alice", AgentTaskStatus.PENDING,
                Map.of("goal", "goal"), null, null, now, now, null);
    }
}
