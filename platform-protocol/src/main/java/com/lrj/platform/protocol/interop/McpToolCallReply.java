package com.lrj.platform.protocol.interop;

public record McpToolCallReply(String tool,
                               boolean success,
                               Object result,
                               String error) {
}
