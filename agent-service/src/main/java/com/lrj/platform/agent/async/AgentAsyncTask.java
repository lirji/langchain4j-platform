package com.lrj.platform.agent.async;

import java.time.Instant;
import java.util.Map;

public record AgentAsyncTask(String taskId,
                             String tenantId,
                             String userId,
                             AgentTaskStatus status,
                             Map<String, Object> input,
                             Object result,
                             String error,
                             Instant createdAt,
                             Instant updatedAt,
                             Instant finishedAt) {

    public AgentAsyncTask {
        input = input == null ? Map.of() : Map.copyOf(input);
    }

    public AgentAsyncTask withStatus(AgentTaskStatus newStatus, Object newResult, String newError) {
        Instant now = Instant.now();
        return new AgentAsyncTask(
                taskId,
                tenantId,
                userId,
                newStatus,
                input,
                newResult,
                newError,
                createdAt,
                now,
                newStatus.isTerminal() ? now : finishedAt);
    }
}
