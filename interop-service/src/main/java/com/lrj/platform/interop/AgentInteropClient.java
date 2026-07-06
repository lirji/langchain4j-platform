package com.lrj.platform.interop;

import com.lrj.platform.protocol.agent.AgentRunReply;

public interface AgentInteropClient {

    AgentRunReply run(String goal);

    Object runAsync(String goal, String webhookUrl);

    Object planDagAndRun(String goal);

    Object planDagAndRunAsync(String goal, String webhookUrl);
}
