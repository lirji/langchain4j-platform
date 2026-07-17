package com.lrj.platform.interop;

import com.lrj.platform.protocol.agent.AgentRunReply;

/**
 * interop-service 到 agent-service 的调用契约：把 MCP 工具调用（{@code platform.agent.*}）代理为
 * agent-service 的同步 ReAct（{@code /agent/run}）、异步任务（{@code /agent/run/async}）以及多 Agent DAG
 * 规划执行（{@code /agent/dag/plan-run[/async]}）。唯一实现为 {@link HttpAgentInteropClient}，
 * 由 {@link InteropToolDispatcher} 调度。
 */
public interface AgentInteropClient {

    AgentRunReply run(String goal);

    Object runAsync(String goal, String webhookUrl);

    Object planDagAndRun(String goal);

    Object planDagAndRunAsync(String goal, String webhookUrl);
}
