package com.lrj.platform.conversation.mcp;

/**
 * 精简对话助手（迁移单体 {@code ai/mcp/McpAssistant}），工具**完全来自 MCP server**（经 {@code toolProvider} 挂载）。
 * 无 ChatMemory、无 RAG 检索——保持 MCP 工具调用演示的纯粹。
 */
public interface McpAssistant {

    String chat(String userMessage);
}
