package com.lrj.platform.protocol.agent;

public record AgentDagCritique(double correctness,
                               double completeness,
                               double clarity,
                               String mainIssue) {
}
