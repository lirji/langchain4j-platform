package com.lrj.platform.protocol.agent;

import java.util.List;

/**
 * 多 Agent DAG 编排的运行响应（{@code POST /agent/dag/run}）：承载目标、拓扑分层、各任务结果
 * {@link AgentDagTaskResult}、综合答案 {@link AgentRunReply} 与发起租户；{@code attempts} 记录
 * 每次（重规划）尝试 {@link AgentDagAttempt}，{@code acceptedByThreshold} 表示最终结果是否达判官阈值。
 */
public record AgentDagRunReply(String goal,
                               List<List<String>> levels,
                               List<AgentDagTaskResult> taskResults,
                               AgentRunReply synthesis,
                               String tenantId,
                               List<AgentDagAttempt> attempts,
                               boolean acceptedByThreshold) {

    public AgentDagRunReply(String goal,
                            List<List<String>> levels,
                            List<AgentDagTaskResult> taskResults,
                            AgentRunReply synthesis,
                            String tenantId) {
        this(goal, levels, taskResults, synthesis, tenantId, List.of(), true);
    }

    public AgentDagRunReply {
        levels = levels == null
                ? List.of()
                : levels.stream().map(List::copyOf).toList();
        taskResults = taskResults == null ? List.of() : List.copyOf(taskResults);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }
}
