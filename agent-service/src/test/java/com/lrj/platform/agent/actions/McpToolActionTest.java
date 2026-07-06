package com.lrj.platform.agent.actions;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolActionTest {

    @Test
    void descriptionListsAvailableTools() {
        McpToolAction action = new McpToolAction(new FakeMcpClient(), new ObjectMapper());

        assertThat(action.name()).isEqualTo("mcp_call");
        assertThat(action.description()).contains("get_weather", "查天气");
    }

    @Test
    void runParsesJsonAndDispatches() {
        FakeMcpClient mcp = new FakeMcpClient();

        String observation = new McpToolAction(mcp, new ObjectMapper())
                .run("{\"tool\":\"get_weather\",\"args\":{\"city\":\"上海\"}}");

        assertThat(observation).isEqualTo("result-for:get_weather");
        assertThat(mcp.lastRequest.name()).isEqualTo("get_weather");
        assertThat(mcp.lastRequest.arguments()).contains("上海");
    }

    @Test
    void missingArgsDefaultsToEmptyObject() {
        FakeMcpClient mcp = new FakeMcpClient();

        new McpToolAction(mcp, new ObjectMapper()).run("{\"tool\":\"list_dir\"}");

        assertThat(mcp.lastRequest.arguments()).isEqualTo("{}");
    }

    @Test
    void missingToolFieldIsCorrectable() {
        String observation = new McpToolAction(new FakeMcpClient(), new ObjectMapper()).run("{\"args\":{}}");

        assertThat(observation).contains("tool");
    }

    @Test
    void invalidJsonIsCorrectable() {
        String observation = new McpToolAction(new FakeMcpClient(), new ObjectMapper()).run("get_weather 上海");

        assertThat(observation).contains("JSON");
    }

    @Test
    void toolReturnsErrorIsText() {
        FakeMcpClient mcp = new FakeMcpClient();
        mcp.returnError = true;

        String observation = new McpToolAction(mcp, new ObjectMapper()).run("{\"tool\":\"get_weather\",\"args\":{}}");

        assertThat(observation).contains("返回错误", "boom");
    }

    @Test
    void executeThrowsIsCorrectable() {
        FakeMcpClient mcp = new FakeMcpClient();
        mcp.throwOnExecute = true;

        String observation = new McpToolAction(mcp, new ObjectMapper()).run("{\"tool\":\"get_weather\",\"args\":{}}");

        assertThat(observation).contains("失败", "transport down");
    }

    static class FakeMcpClient implements McpClient {
        ToolExecutionRequest lastRequest;
        boolean returnError;
        boolean throwOnExecute;

        @Override
        public List<ToolSpecification> listTools() {
            return List.of(
                    ToolSpecification.builder().name("get_weather").description("查天气").build(),
                    ToolSpecification.builder().name("list_dir").description("列目录").build());
        }

        @Override
        public ToolExecutionResult executeTool(ToolExecutionRequest request) {
            lastRequest = request;
            if (throwOnExecute) {
                throw new RuntimeException("transport down");
            }
            return ToolExecutionResult.builder()
                    .isError(returnError)
                    .resultText(returnError ? "boom" : "result-for:" + request.name())
                    .build();
        }

        @Override public String key() { throw new UnsupportedOperationException(); }
        @Override public List<ToolSpecification> listTools(dev.langchain4j.invocation.InvocationContext context) { throw new UnsupportedOperationException(); }
        @Override public ToolExecutionResult executeTool(ToolExecutionRequest request, dev.langchain4j.invocation.InvocationContext context) { throw new UnsupportedOperationException(); }
        @Override public List<dev.langchain4j.mcp.client.McpResource> listResources() { throw new UnsupportedOperationException(); }
        @Override public List<dev.langchain4j.mcp.client.McpResource> listResources(dev.langchain4j.invocation.InvocationContext context) { throw new UnsupportedOperationException(); }
        @Override public List<dev.langchain4j.mcp.client.McpResourceTemplate> listResourceTemplates() { throw new UnsupportedOperationException(); }
        @Override public List<dev.langchain4j.mcp.client.McpResourceTemplate> listResourceTemplates(dev.langchain4j.invocation.InvocationContext context) { throw new UnsupportedOperationException(); }
        @Override public dev.langchain4j.mcp.client.McpReadResourceResult readResource(String uri) { throw new UnsupportedOperationException(); }
        @Override public dev.langchain4j.mcp.client.McpReadResourceResult readResource(String uri, dev.langchain4j.invocation.InvocationContext context) { throw new UnsupportedOperationException(); }
        @Override public void subscribeToResource(String uri) { throw new UnsupportedOperationException(); }
        @Override public void unsubscribeFromResource(String uri) { throw new UnsupportedOperationException(); }
        @Override public List<dev.langchain4j.mcp.client.McpPrompt> listPrompts() { throw new UnsupportedOperationException(); }
        @Override public dev.langchain4j.mcp.client.McpGetPromptResult getPrompt(String name, java.util.Map<String, Object> args) { throw new UnsupportedOperationException(); }
        @Override public void checkHealth() { throw new UnsupportedOperationException(); }
        @Override public void setRoots(List<dev.langchain4j.mcp.client.McpRoot> roots) { throw new UnsupportedOperationException(); }
        @Override public void close() {}
    }
}
