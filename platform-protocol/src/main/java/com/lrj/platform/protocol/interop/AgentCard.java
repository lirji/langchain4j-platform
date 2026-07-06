package com.lrj.platform.protocol.interop;

import java.util.List;
import java.util.Map;

public record AgentCard(String name,
                        String description,
                        String version,
                        List<String> capabilities,
                        Map<String, String> endpoints) {

    public AgentCard {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        endpoints = endpoints == null ? Map.of() : Map.copyOf(endpoints);
    }
}
