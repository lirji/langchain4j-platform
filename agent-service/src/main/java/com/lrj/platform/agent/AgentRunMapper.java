package com.lrj.platform.agent;

import com.lrj.platform.protocol.agent.AgentRunReply;
import com.lrj.platform.protocol.agent.AgentStep;
import com.lrj.platform.security.TenantContext;

/**
 * 把 {@link DeepAgentService.Run} 内部运行结果转换为跨服务契约 {@link AgentRunReply}（含逐步 {@link AgentStep}、
 * finalAnswer、stopReason、depth）的无状态工具类，并从 {@link TenantContext} 补上当前租户 id。
 */
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
