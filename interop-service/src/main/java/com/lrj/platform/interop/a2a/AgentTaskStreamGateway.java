package com.lrj.platform.interop.a2a;

import com.lrj.platform.protocol.agent.AgentTaskView;

import java.util.function.Consumer;

/**
 * interop → agent-service 的异步任务流式代理网关：消费 agent {@code GET /agent/tasks/{taskId}/stream}
 * 的 SSE，把每个任务状态帧回调出来。用于把 A2A {@code message/stream}（deep-research skill）翻成任务级
 * 状态流。progress（非状态）事件在实现里被忽略，只上抛携带 {@code status} 的任务快照。
 */
public interface AgentTaskStreamGateway {

    void streamTask(String taskId,
                    Consumer<AgentTaskView> onUpdate, Runnable onDone, Consumer<Throwable> onError);
}
