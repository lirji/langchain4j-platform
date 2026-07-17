package com.lrj.platform.asynctask;

import com.lrj.platform.protocol.asynctask.AsyncTask;

/**
 * 进程内 Spring 应用事件，承载一次 {@link AsyncTask} 状态快照。由 {@link AsyncTaskController} 在任务
 * 创建/状态更新/租约/取消时发布，供 SSE 推送（{@link AsyncTaskSseService}）、webhook 通知等
 * {@code @EventListener} 消费。
 */
public record AsyncTaskEvent(AsyncTask task) {
}
