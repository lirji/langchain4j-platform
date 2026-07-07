package com.lrj.platform.protocol.agent;

import java.util.Map;

/**
 * 跨服务只读视图：interop-service 反序列化 agent-service 异步任务响应（{@code /agent/run/async}、
 * {@code /agent/tasks/{id}}）的最小契约，字段名与 agent-service 的 {@code AgentAsyncTask} 对齐。
 *
 * <p>{@code status} 用字符串（如 {@code SUCCEEDED}）、时间戳用 ISO-8601 字符串，避免耦合 agent 内部
 * 枚举/{@code Instant} 序列化细节。未知字段由 Spring Boot 默认 ObjectMapper（FAIL_ON_UNKNOWN=false）忽略，
 * 向后兼容 agent 响应演进。
 */
public record AgentTaskView(String taskId,
                            String tenantId,
                            String userId,
                            String status,
                            Map<String, Object> input,
                            Object result,
                            String error,
                            String createdAt,
                            String updatedAt,
                            String finishedAt) {

    public AgentTaskView {
        input = input == null ? Map.of() : Map.copyOf(input);
    }
}
