package com.lrj.platform.interop.a2a;

import com.lrj.platform.protocol.agent.AgentTaskView;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class A2aTaskMapperTest {

    private final A2aTaskMapper mapper = new A2aTaskMapper();

    @Test
    void mapsStatusStringsToTaskStates() {
        assertThat(mapper.toTaskState("PENDING")).isEqualTo(TaskState.SUBMITTED);
        assertThat(mapper.toTaskState("RUNNING")).isEqualTo(TaskState.WORKING);
        assertThat(mapper.toTaskState("SUCCEEDED")).isEqualTo(TaskState.COMPLETED);
        assertThat(mapper.toTaskState("FAILED")).isEqualTo(TaskState.FAILED);
        assertThat(mapper.toTaskState("CANCELLED")).isEqualTo(TaskState.CANCELED);
        assertThat(mapper.toTaskState("something-else")).isEqualTo(TaskState.UNKNOWN);
        assertThat(mapper.toTaskState(null)).isEqualTo(TaskState.UNKNOWN);
    }

    @Test
    void succeededTaskCarriesTextArtifact() {
        AgentTaskView view = new AgentTaskView("t1", "acme", "alice", "SUCCEEDED",
                Map.of("goal", "hi"), Map.of("finalAnswer", "the answer"), null,
                "2026-07-07T00:00:00Z", "2026-07-07T00:01:00Z", "2026-07-07T00:01:00Z");

        A2aTask task = mapper.toA2aTask(view);

        assertThat(task.id()).isEqualTo("t1");
        assertThat(task.status().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(task.status().timestamp()).isEqualTo("2026-07-07T00:01:00Z");
        assertThat(task.artifacts()).hasSize(1);
        assertThat(task.artifacts().get(0).parts().get(0).text()).isEqualTo("the answer");
    }

    @Test
    void failedTaskCarriesErrorInStatusMessage() {
        AgentTaskView view = new AgentTaskView("t2", "acme", "alice", "FAILED",
                Map.of(), null, "boom", "2026-07-07T00:00:00Z", "2026-07-07T00:02:00Z", null);

        A2aTask task = mapper.toA2aTask(view);

        assertThat(task.status().state()).isEqualTo(TaskState.FAILED);
        assertThat(task.artifacts()).isNull();
        assertThat(task.status().message().textContent()).isEqualTo("boom");
    }

    @Test
    void runningTaskHasNoArtifactOrErrorMessage() {
        AgentTaskView view = new AgentTaskView("t3", "acme", "alice", "RUNNING",
                Map.of(), null, null, "2026-07-07T00:00:00Z", null, null);

        A2aTask task = mapper.toA2aTask(view);

        assertThat(task.status().state()).isEqualTo(TaskState.WORKING);
        assertThat(task.status().timestamp()).isEqualTo("2026-07-07T00:00:00Z");
        assertThat(task.artifacts()).isNull();
        assertThat(task.status().message()).isNull();
    }
}
