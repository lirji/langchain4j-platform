package com.lrj.platform.protocol.agent;

import java.util.List;

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
