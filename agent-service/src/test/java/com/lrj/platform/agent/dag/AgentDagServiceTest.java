package com.lrj.platform.agent.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.agent.AgentDecision;
import com.lrj.platform.agent.AgentProperties;
import com.lrj.platform.agent.DeepAgentService;
import com.lrj.platform.protocol.agent.AgentDagCritique;
import com.lrj.platform.protocol.agent.AgentDagRunRequest;
import com.lrj.platform.protocol.agent.AgentDagTask;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentDagServiceTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void runsDagLevelsAndSynthesizesFinalAnswer() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AtomicBoolean downstreamSawUpstream = new AtomicBoolean(false);
        AgentDagService service = service(goal -> {
            if (goal.contains("Synthesize the final answer")) {
                assertThat(goal).contains("result-t1", "result-t2");
                return "final";
            }
            if (goal.contains("Your sub-task [t1]")) {
                return "result-t1";
            }
            if (goal.contains("Your sub-task [t2]")) {
                downstreamSawUpstream.set(goal.contains("result-t1"));
                return "result-t2";
            }
            return "unexpected";
        });

        var reply = service.run(new AgentDagRunRequest("answer with staged research", List.of(
                new AgentDagTask("t1", "collect facts", List.of()),
                new AgentDagTask("t2", "use t1 to conclude", List.of("t1")))));

        assertThat(reply.tenantId()).isEqualTo("acme");
        assertThat(reply.levels()).containsExactly(List.of("t1"), List.of("t2"));
        assertThat(reply.taskResults()).extracting("taskId").containsExactly("t1", "t2");
        assertThat(reply.synthesis().finalAnswer()).isEqualTo("final");
        assertThat(downstreamSawUpstream).isTrue();
    }

    @Test
    void topologicalLevelsHandlesDiamondDag() {
        AgentDagService service = service(goal -> "ok");

        var levels = service.topologicalLevels(List.of(
                new AgentDagTask("t1", "root", List.of()),
                new AgentDagTask("t2", "mid a", List.of("t1")),
                new AgentDagTask("t3", "mid b", List.of("t1")),
                new AgentDagTask("t4", "leaf", List.of("t2", "t3"))));

        assertThat(levels).hasSize(3);
        assertThat(levels.get(0)).extracting(AgentDagTask::id).containsExactly("t1");
        assertThat(levels.get(1)).extracting(AgentDagTask::id).containsExactlyInAnyOrder("t2", "t3");
        assertThat(levels.get(2)).extracting(AgentDagTask::id).containsExactly("t4");
    }

    @Test
    void cycleReturnsNullForControllerToReject() {
        AgentDagService service = service(goal -> "ok");

        assertThat(service.topologicalLevels(List.of(
                new AgentDagTask("t1", "a", List.of("t2")),
                new AgentDagTask("t2", "b", List.of("t1"))))).isNull();
    }

    @Test
    void duplicateTaskIdIsRejected() {
        AgentDagService service = service(goal -> "ok");

        assertThatThrownBy(() -> service.run(new AgentDagRunRequest("goal", List.of(
                new AgentDagTask("t1", "a", List.of()),
                new AgentDagTask("t1", "b", List.of())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate task id");
    }

    @Test
    void planAndRunUsesPlannerOutput() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AgentDagService service = service(
                goal -> goal.contains("Synthesize the final answer") ? "final" : "worker",
                goal -> new AgentDagPlan(List.of(
                        new AgentDagPlan.Task("t1", "first planned task", List.of()),
                        new AgentDagPlan.Task("t2", "second planned task", List.of("t1")))));

        var reply = service.planAndRun("plan this");

        assertThat(reply.levels()).containsExactly(List.of("t1"), List.of("t2"));
        assertThat(reply.taskResults()).extracting("description")
                .containsExactly("first planned task", "second planned task");
        assertThat(reply.synthesis().finalAnswer()).isEqualTo("final");
    }

    @Test
    void replanRunsSecondAttemptWhenCritiqueBelowThreshold() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AgentDagProperties properties = new AgentDagProperties();
        properties.getReplan().setEnabled(true);
        properties.getReplan().setThreshold(0.8);
        properties.getReplan().setMaxReplans(1);
        AgentDagService service = service(
                goal -> goal.contains("revised task") ? "revised-final" : "weak-final",
                goal -> new AgentDagPlan(List.of(new AgentDagPlan.Task("t1", goal, List.of()))),
                (question, answer) -> answer.contains("weak")
                        ? new AgentDagCritique(0.6, 0.5, 0.7, "missing details")
                        : new AgentDagCritique(0.9, 0.9, 0.8, "n/a"),
                (goal, previousPlan, previousAnswer, correctness, completeness, clarity, mainIssue) ->
                        new AgentDagPlan(List.of(new AgentDagPlan.Task("t1", "revised task", List.of()))),
                properties);

        var reply = service.run(new AgentDagRunRequest("goal", List.of(
                new AgentDagTask("t1", "initial task", List.of()))));

        assertThat(reply.attempts()).hasSize(2);
        assertThat(reply.acceptedByThreshold()).isTrue();
        assertThat(reply.synthesis().finalAnswer()).isEqualTo("revised-final");
    }

    @Test
    void runEmitsDagProgressEvents() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        List<String> events = new ArrayList<>();
        AgentDagProperties properties = new AgentDagProperties();
        properties.getReplan().setEnabled(true);
        properties.getReplan().setThreshold(0.8);
        properties.getReplan().setMaxReplans(1);
        AgentDagService service = service(
                goal -> goal.contains("revised task") ? "revised-final" : "weak-final",
                goal -> new AgentDagPlan(List.of(new AgentDagPlan.Task("t1", goal, List.of()))),
                (question, answer) -> answer.contains("weak")
                        ? new AgentDagCritique(0.6, 0.5, 0.7, "missing details")
                        : new AgentDagCritique(0.9, 0.9, 0.8, "n/a"),
                (goal, previousPlan, previousAnswer, correctness, completeness, clarity, mainIssue) ->
                        new AgentDagPlan(List.of(new AgentDagPlan.Task("t1", "revised task", List.of()))),
                properties);

        service.run(new AgentDagRunRequest("goal", List.of(
                new AgentDagTask("t1", "initial task", List.of()))), (event, data) -> events.add(event));

        assertThat(events).contains(
                "dag-levels",
                "dag-worker-start",
                "dag-worker-result",
                "dag-synthesis-start",
                "dag-synthesis-result",
                "dag-critique",
                "dag-replan",
                "dag-replanned");
    }

    private static AgentDagService service(Answerer answerer) {
        return service(answerer, goal -> new AgentDagPlan(List.of(
                new AgentDagPlan.Task("t1", goal, List.of()))));
    }

    private static AgentDagService service(Answerer answerer, AgentDagPlanner planner) {
        return service(
                answerer,
                planner,
                (question, answer) -> new AgentDagCritique(1.0, 1.0, 1.0, "n/a"),
                (goal, previousPlan, previousAnswer, correctness, completeness, clarity, mainIssue) ->
                        new AgentDagPlan(List.of(new AgentDagPlan.Task("t1", goal, List.of()))),
                defaultProperties());
    }

    private static AgentDagService service(Answerer answerer,
                                           AgentDagPlanner planner,
                                           AgentDagCritic critic,
                                           AgentDagReplanner replanner,
                                           AgentDagProperties properties) {
        DeepAgentService agent = new DeepAgentService(
                (goal, actions, scratchpad, history) -> new AgentDecision("", "finish", "", "", answerer.answer(goal)),
                List.of(),
                new AgentProperties());
        return new AgentDagService(agent, Runnable::run, properties, planner, critic, replanner, new ObjectMapper());
    }

    private static AgentDagProperties defaultProperties() {
        AgentDagProperties properties = new AgentDagProperties();
        properties.setMaxTasks(6);
        return properties;
    }

    private interface Answerer {
        String answer(String goal);
    }
}
