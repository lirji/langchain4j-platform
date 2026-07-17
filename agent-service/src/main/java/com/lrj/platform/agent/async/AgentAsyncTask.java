package com.lrj.platform.agent.async;

import java.time.Instant;
import java.util.Map;

/**
 * agent 异步任务的不可变状态记录。承载 taskId、所属租户/用户、当前 {@link AgentTaskStatus}、输入、结果或错误
 * 及各时间戳；{@link #withStatus(AgentTaskStatus, Object, String)} 派生新状态副本并在进入终态时补 finishedAt。
 * 由 {@link AgentAsyncTaskService} 生成与流转，可选镜像到外部 async-task-service。
 */
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
