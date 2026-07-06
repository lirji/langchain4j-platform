package com.lrj.platform.agent.async;

@FunctionalInterface
public interface AgentTaskProgressSink {

    void emit(String event, Object data);

    static AgentTaskProgressSink noop() {
        return (event, data) -> {
        };
    }
}
