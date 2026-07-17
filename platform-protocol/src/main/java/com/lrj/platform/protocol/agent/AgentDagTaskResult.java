package com.lrj.platform.protocol.agent;

import java.util.List;

/**
 * 多 Agent DAG 中单个子任务的执行结果：回带任务 {@code taskId}、描述、依赖，及该子任务的
 * ReAct 运行产物 {@link AgentRunReply}。见 {@link AgentDagRunReply}。
 */
public record AgentDagTaskResult(String taskId,
                                 String description,
                                 List<String> dependsOn,
                                 AgentRunReply result) {

    public AgentDagTaskResult {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
