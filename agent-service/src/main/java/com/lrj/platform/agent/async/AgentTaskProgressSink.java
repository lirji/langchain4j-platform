package com.lrj.platform.agent.async;

import java.util.concurrent.CancellationException;

/**
 * Agent 任务执行过程向外发射进度的回调接口（函数式）。执行侧调用 {@link #emit(String, Object)}
 * 上报中间步骤，并可通过 {@link #isCancelled()} / {@link #throwIfCancelled()} 感知取消信号
 * （取消时抛 {@link CancellationException}）。{@link #noop()} 提供空实现，用于同步执行或无需上报的场景。
 */
@FunctionalInterface
public interface AgentTaskProgressSink {

    void emit(String event, Object data);

    default boolean isCancelled() {
        return false;
    }

    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("agent task cancelled");
        }
    }

    static AgentTaskProgressSink noop() {
        return (event, data) -> {
        };
    }
}
