package com.lrj.platform.protocol.agent;

import java.util.List;

public record AgentRunReply(String goal,
                            List<AgentStep> steps,
                            String finalAnswer,
                            String stopReason,
                            int depth,
                            String tenantId) {

    public AgentRunReply {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
