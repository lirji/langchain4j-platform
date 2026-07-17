package com.lrj.platform.protocol.interop;

/**
 * MCP 工具调用的结果（跨服务 DTO）。
 * 回传被调用的 {@code tool} 名称、{@code success} 是否成功、成功时的 {@code result} 结果与失败时的 {@code error} 错误信息，
 * 是 {@link McpToolCallRequest} 的对应应答。
 */
public record McpToolCallReply(String tool,
                               boolean success,
                               Object result,
                               String error) {
}
