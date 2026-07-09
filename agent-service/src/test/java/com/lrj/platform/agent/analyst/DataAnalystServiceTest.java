package com.lrj.platform.agent.analyst;

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
 * 数据分析智能体：验证它用「专用 analyst planner」的拆解喂给现有 DAG 引擎，并正确收口 synthesis；
 * 以及 planner 空返回时回退单任务。LLM/planner 全用 lambda 实现（不 mock），复用真实 AgentDagService。
 */
class DataAnalystServiceTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void analyzeUsesAnalystPlannerDecompositionAndSynthesizes() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        // analyst planner 拆成 探表 → 取数(依赖探表) 两级
        DataAnalystPlanner analystPlanner = goal -> new AgentDagPlan(List.of(
                new AgentDagPlan.Task("t1", "用 schema_explore 查看 orders 表结构", List.of()),
                new AgentDagPlan.Task("t2", "基于 t1 用 analytics_sql 查询上月订单数", List.of("t1"))));

        DataAnalystService service = new DataAnalystService(analystPlanner,
                dag(goal -> goal.contains("Synthesize the final answer") ? "final-analysis" : "worker-result"));

        AgentDagRunReply reply = service.analyze("上月订单数是多少?");

        assertThat(reply.levels()).containsExactly(List.of("t1"), List.of("t2"));
        assertThat(reply.taskResults()).extracting("description")
                .containsExactly("用 schema_explore 查看 orders 表结构", "基于 t1 用 analytics_sql 查询上月订单数");
        assertThat(reply.synthesis().finalAnswer()).isEqualTo("final-analysis");
        assertThat(reply.tenantId()).isEqualTo("acme");
    }

    @Test
    void analyzeFallsBackToSingleTaskWhenPlannerEmpty() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        DataAnalystPlanner emptyPlanner = goal -> new AgentDagPlan(List.of());

        DataAnalystService service = new DataAnalystService(emptyPlanner,
                dag(goal -> goal.contains("Synthesize the final answer") ? "final" : "worker"));

        AgentDagRunReply reply = service.analyze("随便问问");

        assertThat(reply.levels()).containsExactly(List.of("t1"));
        assertThat(reply.taskResults()).extracting("description").containsExactly("随便问问");
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
