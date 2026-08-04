package com.lrj.platform.interop;

import com.lrj.platform.protocol.interop.AgentCapabilityRegistry;

import java.util.Optional;

/** Local/test LKG store. Production wiring uses Redis. */
public class InMemoryCapabilityRegistryStore implements CapabilityRegistryStore {

    private volatile AgentCapabilityRegistry registry;

    @Override
    public Optional<AgentCapabilityRegistry> load() {
        return Optional.ofNullable(registry);
    }

    @Override
    public void save(AgentCapabilityRegistry registry) {
        this.registry = registry;
    }
}
