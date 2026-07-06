package com.lrj.platform.protocol.agent;

import java.util.List;

public record AgentDagTaskResult(String taskId,
                                 String description,
                                 List<String> dependsOn,
                                 AgentRunReply result) {

    public AgentDagTaskResult {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
