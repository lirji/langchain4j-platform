package com.lrj.platform.agent.async;

/**
 * Agent 异步任务的生命周期状态：{@code PENDING} → {@code RUNNING} → 终态
 * {@code SUCCEEDED}/{@code FAILED}/{@code CANCELLED}。{@link #isTerminal()} 判定是否已到终态，
 * 供 SSE 关闭连接、webhook 回调触发等逻辑使用。
 */
public enum AgentTaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
