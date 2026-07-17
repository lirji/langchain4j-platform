package com.lrj.platform.protocol.agent;

/**
 * 单个 ReAct Agent 运行的请求（{@code POST /agent/run}）：给定目标 {@code goal}，可选
 * {@code webhookUrl} 用于异步完成回调。
 */
public record AgentRunRequest(String goal, String webhookUrl) {

    public AgentRunRequest(String goal) {
        this(goal, null);
    }
}
