package com.lrj.platform.interop;

import com.lrj.platform.protocol.interop.AgentCapabilityRegistry;

import java.util.Optional;

/** Durable last-known-good capability registry store. */
public interface CapabilityRegistryStore {

    Optional<AgentCapabilityRegistry> load();

    void save(AgentCapabilityRegistry registry);
}
