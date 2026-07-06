package com.lrj.platform.protocol.agent;

import java.util.List;

public record AgentDagTask(String id,
                           String description,
                           List<String> dependsOn) {

    public AgentDagTask {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
