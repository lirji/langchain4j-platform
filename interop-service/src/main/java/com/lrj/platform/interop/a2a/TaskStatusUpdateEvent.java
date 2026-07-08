package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A2A {@code message/stream} 的状态更新事件（{@code kind="status-update"}）。
 * {@code final=true} 标记流的最后一帧（任务到达终态）。移植自单体 {@code a2a/protocol}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskStatusUpdateEvent(String taskId,
                                    String contextId,
                                    A2aTaskStatus status,
                                    @JsonProperty("final") boolean isFinal) {

    @JsonProperty("kind")
    public String kind() {
        return "status-update";
    }
}
