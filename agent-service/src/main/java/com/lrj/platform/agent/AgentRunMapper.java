package com.lrj.platform.agent;

import com.lrj.platform.protocol.agent.AgentRunReply;
import com.lrj.platform.protocol.agent.AgentStep;
import com.lrj.platform.security.TenantContext;

public final class AgentRunMapper {

    private AgentRunMapper() {
    }

    public static AgentRunReply toReply(DeepAgentService.Run run) {
        return new AgentRunReply(
                run.goal(),
                run.steps().stream()
                        .map(step -> new AgentStep(
                                step.n(),
                                step.thought(),
                                step.action(),
                                step.actionInput(),
                                step.observation()))
                        .toList(),
                run.finalAnswer(),
                run.stopReason(),
                run.depth(),
                TenantContext.current().tenantId());
    }
}
