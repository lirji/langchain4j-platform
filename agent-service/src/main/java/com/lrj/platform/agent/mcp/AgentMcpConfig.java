package com.lrj.platform.agent.mcp;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * agent-service 的 MCP 客户端装配：按 {@link AgentMcpProperties} 选择 stdio 或 streamable-http 传输，
 * 构建单个 {@link McpClient} bean（关闭时调用 close），供 MCP 工具动作调用外部 MCP 服务器。
 * 仅在 {@code app.agent.enabled} 与 {@code app.agent.mcp.enabled} 同时为 true 时生效。
 */
@Configuration
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.mcp.enabled"}, havingValue = "true")
public class AgentMcpConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentMcpConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "app.agent.mcp")
    AgentMcpProperties agentMcpProperties() {
        return new AgentMcpProperties();
    }

    @Bean(destroyMethod = "close")
    McpClient agentMcpClient(AgentMcpProperties properties) {
        McpTransport transport;
        if ("http".equalsIgnoreCase(properties.getTransport())) {
            transport = StreamableHttpMcpTransport.builder()
                    .url(properties.getHttp().getUrl())
                    .logRequests(properties.isLogEvents())
                    .logResponses(properties.isLogEvents())
                    .build();
            log.info("agent MCP transport: http {}", properties.getHttp().getUrl());
        } else {
            transport = new StdioMcpTransport.Builder()
                    .command(properties.getStdio().getCommand())
                    .logEvents(properties.isLogEvents())
                    .build();
            log.info("agent MCP transport: stdio {}", properties.getStdio().getCommand());
        }
        return new DefaultMcpClient.Builder()
                .transport(transport)
                .build();
    }
}
