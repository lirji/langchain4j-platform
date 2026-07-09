package com.lrj.platform.agent.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.agent.AgentDecision;
import com.lrj.platform.agent.AgentProperties;
import com.lrj.platform.agent.DeepAgentService;
import com.lrj.platform.agent.dag.AgentDagCritic;
import com.lrj.platform.agent.dag.AgentDagPlan;
import com.lrj.platform.agent.dag.AgentDagPlanner;
import com.lrj.platform.agent.dag.AgentDagProperties;
import com.lrj.platform.agent.dag.AgentDagReplanner;
import com.lrj.platform.agent.dag.AgentDagService;
import com.lrj.platform.protocol.agent.AgentDagCritique;
import com.lrj.platform.protocol.agent.AgentDagRunReply;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务流程智能体：验证专用 ProcessPlanner 的「发起→查询汇报」拆解喂给现有 DAG 引擎并收口；
 * 及 planner 空返回回退单任务。LLM/planner 用 lambda（不 mock），复用真实 AgentDagService。
 */
class ProcessServiceTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void runUsesProcessPlannerDecompositionAndSynthesizes() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        ProcessPlanner planner = goal -> new AgentDagPlan(List.of(
                new AgentDagPlan.Task("t1", "用 refund_start 发起退款诉求", List.of()),
                new AgentDagPlan.Task("t2", "用 workflow_status 查 t1 的 instanceId 并如实汇报", List.of("t1"))));

        ProcessService service = new ProcessService(planner,
                dag(goal -> goal.contains("Synthesize the final answer") ? "已发起，需人工审批" : "worker-result"));

        AgentDagRunReply reply = service.run("帮我发起退款并告诉我进展");

        assertThat(reply.levels()).containsExactly(List.of("t1"), List.of("t2"));
        assertThat(reply.taskResults()).extracting("description")
                .containsExactly("用 refund_start 发起退款诉求", "用 workflow_status 查 t1 的 instanceId 并如实汇报");
        assertThat(reply.synthesis().finalAnswer()).isEqualTo("已发起，需人工审批");
        assertThat(reply.tenantId()).isEqualTo("acme");
    }

    @Test
    void runFallsBackToSingleTaskWhenPlannerEmpty() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        ProcessPlanner emptyPlanner = goal -> new AgentDagPlan(List.of());

        ProcessService service = new ProcessService(emptyPlanner,
                dag(goal -> goal.contains("Synthesize the final answer") ? "final" : "worker"));

        AgentDagRunReply reply = service.run("随便一个流程诉求");

        assertThat(reply.levels()).containsExactly(List.of("t1"));
        assertThat(reply.taskResults()).extracting("description").containsExactly("随便一个流程诉求");
        assertThat(reply.synthesis().finalAnswer()).isEqualTo("final");
    }

    private static AgentDagService dag(Answerer answerer) {
        DeepAgentService agent = new DeepAgentService(
                (goal, actions, scratchpad, history) -> new AgentDecision("", "finish", "", "", answerer.answer(goal)),
                List.of(),
                new AgentProperties());
        AgentDagPlanner genericPlanner = goal -> new AgentDagPlan(List.of(
                new AgentDagPlan.Task("t1", goal, List.of())));
        AgentDagCritic critic = (question, answer) -> new AgentDagCritique(1.0, 1.0, 1.0, "n/a");
        AgentDagReplanner replanner =
                (goal, previousPlan, previousAnswer, correctness, completeness, clarity, mainIssue) ->
                        new AgentDagPlan(List.of(new AgentDagPlan.Task("t1", goal, List.of())));
        AgentDagProperties properties = new AgentDagProperties();
        properties.setMaxTasks(6);
        return new AgentDagService(agent, Runnable::run, properties, genericPlanner, critic, replanner, new ObjectMapper());
    }

    private interface Answerer {
        String answer(String goal);
    }
}
