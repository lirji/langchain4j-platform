package com.lrj.platform.protocol.agent;

import java.util.List;

/**
 * 多 Agent DAG 中的单个子任务定义：{@code id}、任务描述 {@code description} 及其依赖的前置任务 id
 * 列表 {@code dependsOn}（据此做拓扑分层调度）。见 {@link AgentDagRunRequest}。
 */
public record AgentDagTask(String id,
                           String description,
                           List<String> dependsOn) {

    public AgentDagTask {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
