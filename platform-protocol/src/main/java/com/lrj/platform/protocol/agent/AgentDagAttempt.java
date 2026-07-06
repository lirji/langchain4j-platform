package com.lrj.platform.protocol.agent;

import java.util.List;

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
