package com.lrj.platform.protocol.agent;

public record AgentRunRequest(String goal, String webhookUrl) {

    public AgentRunRequest(String goal) {
        this(goal, null);
    }
}
