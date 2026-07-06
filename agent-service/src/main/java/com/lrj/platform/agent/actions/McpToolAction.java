package com.lrj.platform.agent.actions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.agent.AgentAction;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(McpClient.class)
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.mcp.enabled"}, havingValue = "true")
public class McpToolAction implements AgentAction {

    private static final Logger log = LoggerFactory.getLogger(McpToolAction.class);

    private final McpClient mcp;
    private final ObjectMapper mapper;
    private final String catalog;

    public McpToolAction(McpClient mcp, ObjectMapper mapper) {
        this.mcp = mcp;
        this.mapper = mapper;
        this.catalog = buildCatalog(mcp);
    }

    @Override
    public String name() {
        return "mcp_call";
    }

    @Override
    public String description() {
        return "调用 MCP server 暴露的外部工具。actionInput 填 JSON：{\"tool\":\"工具名\",\"args\":{参数对象}}。"
                + "可用工具：\n" + catalog;
    }

    @Override
    public String run(String input) {
        if (input == null || input.isBlank()) {
            return "入参为空：actionInput 请填 JSON {\"tool\":\"工具名\",\"args\":{...}}。";
        }
        String tool;
        String argsJson;
        try {
            JsonNode node = mapper.readTree(input.trim());
            JsonNode toolNode = node.get("tool");
            if (toolNode == null || toolNode.asText().isBlank()) {
                return "JSON 缺少 \"tool\" 字段；格式 {\"tool\":\"工具名\",\"args\":{...}}。";
            }
            tool = toolNode.asText().trim();
            JsonNode argsNode = node.get("args");
            argsJson = argsNode == null || argsNode.isNull() ? "{}" : argsNode.toString();
        } catch (Exception ex) {
            return "actionInput 不是合法 JSON（" + ex.getMessage() + "）；格式 {\"tool\":\"工具名\",\"args\":{...}}。";
        }
        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .name(tool)
                    .arguments(argsJson)
                    .build();
            ToolExecutionResult result = mcp.executeTool(request);
            String text = result == null ? "" : result.resultText();
            if (result != null && result.isError()) {
                return "MCP 工具 '" + tool + "' 返回错误：" + text;
            }
            return text == null || text.isBlank() ? "(MCP 工具 '" + tool + "' 返回空结果)" : text;
        } catch (Exception ex) {
            return "调用 MCP 工具 '" + tool + "' 失败：" + ex.getMessage() + "（检查工具名/参数后重试或改走其他动作）";
        }
    }

    private static String buildCatalog(McpClient mcp) {
        try {
            StringBuilder builder = new StringBuilder();
            for (ToolSpecification tool : mcp.listTools()) {
                builder.append("  - ").append(tool.name());
                if (tool.description() != null && !tool.description().isBlank()) {
                    builder.append(": ").append(tool.description().trim());
                }
                builder.append('\n');
            }
            return builder.isEmpty() ? "  (MCP server 未暴露工具)" : builder.toString().stripTrailing();
        } catch (Exception ex) {
            log.warn("failed to list MCP tools: {}", ex.toString());
            return "  (无法列出 MCP 工具：" + ex.getMessage() + ")";
        }
    }
}
