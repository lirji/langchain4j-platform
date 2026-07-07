package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A2A 任务状态。序列化用带连字符的 wire value（{@code input-required}）—— {@code @JsonValue} 控制。
 * 与 agent-service {@code AgentTaskStatus} 的映射见 {@link A2aTaskMapper}。
 */
public enum TaskState {
    SUBMITTED("submitted"),
    WORKING("working"),
    INPUT_REQUIRED("input-required"),
    COMPLETED("completed"),
    CANCELED("canceled"),
    FAILED("failed"),
    UNKNOWN("unknown");

    private final String value;

    TaskState(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
