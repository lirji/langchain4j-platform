package com.lrj.platform.protocol.interop;

import java.util.Map;

/**
 * MCP 工具的元数据描述（跨服务 DTO）。
 * 在 MCP surface 上声明一个可用工具的 {@code name}、{@code description} 及其入参 {@code inputSchema}（JSON Schema），
 * 供对端发现工具并按 schema 构造 {@link McpToolCallRequest}。紧凑构造器对 inputSchema 做空值兜底与不可变拷贝。
 */
public record McpToolDescriptor(String name,
                                String description,
                                Map<String, Object> inputSchema) {

    public McpToolDescriptor {
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }
}
