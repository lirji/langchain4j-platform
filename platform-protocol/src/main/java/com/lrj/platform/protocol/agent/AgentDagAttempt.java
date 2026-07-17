package com.lrj.platform.protocol.agent;

import java.util.List;

/**
 * 多 Agent DAG 编排中的一次执行/重规划尝试快照：记录第 {@code n} 次尝试的拓扑分层
 * ({@code levels})、各任务结果 {@link AgentDagTaskResult}、综合答案 {@link AgentRunReply}
 * 与判官打分 {@link AgentDagCritique}，{@code aggregate} 为该次尝试的综合得分。
 * DAG 未达阈值触发重规划时逐次累积，见 {@link AgentDagRunReply}。
 */
public record AgentDagAttempt(int n,
                              List<List<String>> levels,
                              List<AgentDagTaskResult> taskResults,
                              AgentRunReply synthesis,
                              AgentDagCritique critique,
                              double aggregate) {

    public AgentDagAttempt {
        levels = levels == null
                ? List.of()
                : levels.stream().map(List::copyOf).toList();
        taskResults = taskResults == null ? List.of() : List.copyOf(taskResults);
    }
}
