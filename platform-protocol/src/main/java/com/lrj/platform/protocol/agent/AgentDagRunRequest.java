package com.lrj.platform.protocol.agent;

import java.util.List;

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
