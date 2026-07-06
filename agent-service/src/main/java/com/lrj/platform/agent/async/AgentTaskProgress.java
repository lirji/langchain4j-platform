package com.lrj.platform.agent.async;

import java.time.Instant;

public record AgentTaskProgress(String taskId,
                                String event,
                                Object data,
                                Instant ts) {

    public AgentTaskProgress {
        ts = ts == null ? Instant.now() : ts;
    }
}
