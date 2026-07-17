package com.lrj.platform.agent.async;

/**
 * 包装单条 {@link AgentTaskProgress} 的 Spring 应用事件。进度产生时发布，
 * 由 {@link AgentTaskSseService} 监听并转发给对应任务的 SSE 订阅方。
 */
public record AgentTaskProgressEvent(AgentTaskProgress progress) {
}
