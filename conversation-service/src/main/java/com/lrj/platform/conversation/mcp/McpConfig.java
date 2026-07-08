package com.lrj.platform.conversation.mcp;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MCP 驱动对话装配（迁移单体 {@code McpConfig}）。默认关；开启后按 {@code app.conversation.mcp.transport}
 * （stdio 默认 / http）建立 MCP client，工具经 {@link McpToolProvider} 挂到 {@link McpAssistant}。
 */
@Configuration
@ConditionalOnProperty(name = "app.conversation.mcp.enabled", havingValue = "true")
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    @Bean(destroyMethod = "close")
    McpClient mcpClient(McpProperties props) {
        McpTransport transport;
        if ("http".equalsIgnoreCase(props.getTransport())) {
            transport = StreamableHttpMcpTransport.builder()
                    .url(props.getHttp().getUrl())
                    .logRequests(props.isLogEvents())
                    .logResponses(props.isLogEvents())
                    .build();
            log.info("MCP transport: http {}", props.getHttp().getUrl());
        } else {
            transport = new StdioMcpTransport.Builder()
                    .command(props.getStdio().getCommand())
                    .logEvents(props.isLogEvents())
                    .build();
            log.info("MCP transport: stdio {}", props.getStdio().getCommand());
        }
        return new DefaultMcpClient.Builder()
                .transport(transport)
                .build();
    }

    @Bean
    ToolProvider mcpToolProvider(McpClient mcpClient) {
        return McpToolProvider.builder()
                .mcpClients(List.of(mcpClient))
                .build();
    }

    @Bean
    McpAssistant mcpAssistant(ChatModel chatModel, ToolProvider mcpToolProvider) {
        return AiServices.builder(McpAssistant.class)
                .chatModel(chatModel)
                .toolProvider(mcpToolProvider)
                .build();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.conversation.mcp")
    McpProperties mcpProperties() {
        return new McpProperties();
    }

    public static class McpProperties {
        private String transport = "stdio";
        private boolean logEvents = true;
        private Stdio stdio = new Stdio();
        private Http http = new Http();

        public String getTransport() {
            return transport;
        }

        public void setTransport(String transport) {
            this.transport = transport;
        }

        public boolean isLogEvents() {
            return logEvents;
        }

        public void setLogEvents(boolean logEvents) {
            this.logEvents = logEvents;
        }

        public Stdio getStdio() {
            return stdio;
        }

        public void setStdio(Stdio stdio) {
            this.stdio = stdio;
        }

        public Http getHttp() {
            return http;
        }

        public void setHttp(Http http) {
            this.http = http;
        }

        public static class Stdio {
            private List<String> command = List.of();

            public List<String> getCommand() {
                return command;
            }

            public void setCommand(List<String> command) {
                this.command = command;
            }
        }

        public static class Http {
            private String url = "http://localhost:3001/mcp";

            public String getUrl() {
                return url;
            }

            public void setUrl(String url) {
                this.url = url;
            }
        }
    }
}
