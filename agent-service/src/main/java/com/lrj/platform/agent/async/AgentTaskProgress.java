package com.lrj.platform.agent.async;

import java.time.Instant;

/**
 * Agent 异步任务的一条进度事件。承载 {@code taskId}、进度事件名 {@code event}、任意结构的
 * {@code data} 载荷以及产生时间戳 {@code ts}（缺省补 {@link Instant#now()}）。
 * 由 {@link AgentTaskProgressSink} 产出，包装进 {@link AgentTaskProgressEvent} 后经
 * {@link AgentTaskSseService} 推送给 SSE 订阅方，实现 ReAct 执行过程的实时观测。
 */
public record AgentTaskProgress(String taskId,
                                String event,
                                Object data,
                                Instant ts) {

    public AgentTaskProgress {
        ts = ts == null ? Instant.now() : ts;
    }
}
