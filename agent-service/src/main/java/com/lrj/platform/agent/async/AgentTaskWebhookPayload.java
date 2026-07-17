package com.lrj.platform.agent.async;

import java.time.Instant;
import java.util.Map;

/**
 * Agent 异步任务终态 webhook 回调的 JSON 载荷。承载任务标识、租户/用户、最终 {@link AgentTaskStatus}、
 * 原始入参、结果或错误以及各时间戳。{@link #from(AgentAsyncTask)} 由任务快照构造，
 * 供 {@link AgentTaskWebhookNotifier} POST 给调用方回调地址。
 */
public record AgentTaskWebhookPayload(String taskId,
                                      String tenantId,
                                      String userId,
                                      AgentTaskStatus status,
                                      Map<String, Object> input,
                                      Object result,
                                      String error,
                                      Instant createdAt,
                                      Instant updatedAt,
                                      Instant finishedAt) {

    public static AgentTaskWebhookPayload from(AgentAsyncTask task) {
        return new AgentTaskWebhookPayload(
                task.taskId(),
                task.tenantId(),
                task.userId(),
                task.status(),
                task.input(),
                task.result(),
                task.error(),
                task.createdAt(),
                task.updatedAt(),
                task.finishedAt());
    }
}
