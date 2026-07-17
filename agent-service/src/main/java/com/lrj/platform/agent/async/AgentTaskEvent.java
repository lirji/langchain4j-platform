package com.lrj.platform.agent.async;

/**
 * Agent 异步任务状态变更的 Spring 应用事件，仅包装一份最新的 {@link AgentAsyncTask} 快照。
 * 任务创建/流转/终态时发布，由 {@link AgentTaskSseService}（推 SSE）与
 * {@link AgentTaskWebhookNotifier}（终态回调 webhook）等监听方消费。
 */
public record AgentTaskEvent(AgentAsyncTask task) {
}
