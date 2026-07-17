package com.lrj.platform.protocol.interop;

import java.util.Map;

/**
 * MCP 工具调用请求（跨服务 DTO）。
 * 指定要调用的 {@code tool} 工具名与 {@code arguments} 入参映射，其应答见 {@link McpToolCallReply}。
 * 紧凑构造器对 arguments 做空值兜底与不可变拷贝。
 */
public record McpToolCallRequest(String tool,
                                 Map<String, Object> arguments) {

    public McpToolCallRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
