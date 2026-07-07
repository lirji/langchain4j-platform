package com.lrj.platform.interop;

import com.lrj.platform.protocol.interop.McpToolDescriptor;

import java.util.List;

/**
 * live capability discovery 的下游拉取端：从 agent-service {@code GET /agent/capabilities} 拉取
 * agent 当前暴露的能力（以 {@link McpToolDescriptor} 表达）。实现挂租户/trace forwarder。
 */
public interface AgentCapabilityClient {

    List<McpToolDescriptor> discoverTools();
}
