package com.lrj.platform.interop;

import com.lrj.platform.protocol.interop.AgentCapabilityRegistry;

/**
 * live capability discovery 的下游拉取端：从 AgentScope 版本化 registry 拉取能力。
 */
public interface AgentCapabilityClient {

    AgentCapabilityRegistry discoverRegistry();
}
