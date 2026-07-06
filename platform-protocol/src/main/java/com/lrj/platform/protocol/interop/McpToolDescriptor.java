package com.lrj.platform.protocol.interop;

import java.util.Map;

public record McpToolDescriptor(String name,
                                String description,
                                Map<String, Object> inputSchema) {

    public McpToolDescriptor {
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }
}
