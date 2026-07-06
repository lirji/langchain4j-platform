package com.lrj.platform.protocol.agent;

public record AgentStep(int n, String thought, String action, String actionInput, String observation) {
}
