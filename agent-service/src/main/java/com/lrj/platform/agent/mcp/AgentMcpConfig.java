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
