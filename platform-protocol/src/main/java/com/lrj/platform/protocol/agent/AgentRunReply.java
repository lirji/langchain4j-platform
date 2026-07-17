package com.lrj.platform.protocol.agent;

import java.util.List;

/**
 * 单个 ReAct Agent 运行的响应（{@code POST /agent/run}）：承载目标、逐步推理 trace
 * {@link AgentStep} 列表、最终答案、停止原因、达到的深度与发起租户。
 */
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
