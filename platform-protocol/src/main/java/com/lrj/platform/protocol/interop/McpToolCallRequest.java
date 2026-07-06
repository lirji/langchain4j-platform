package com.lrj.platform.protocol.interop;

import java.util.Map;

public record McpToolCallRequest(String tool,
                                 Map<String, Object> arguments) {

    public McpToolCallRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
