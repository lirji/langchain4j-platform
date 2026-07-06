package com.lrj.platform.agent.async;

import java.util.concurrent.CancellationException;

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
