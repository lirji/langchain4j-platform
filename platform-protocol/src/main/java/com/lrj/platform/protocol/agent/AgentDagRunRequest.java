package com.lrj.platform.protocol.agent;

import java.util.List;

/**
 * 多 Agent DAG 编排的运行请求（{@code POST /agent/dag/run}）：给定总目标 {@code goal} 与一组带依赖的
 * 子任务 {@link AgentDagTask}，可选 {@code webhookUrl} 用于异步完成回调。
 */
public record AgentDagRunRequest(String goal,
                                 List<AgentDagTask> tasks,
                                 String webhookUrl) {

    public AgentDagRunRequest(String goal, List<AgentDagTask> tasks) {
        this(goal, tasks, null);
    }

    public AgentDagRunRequest {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
