package com.lrj.platform.conversation.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配守卫：MCP 默认关（不设 {@code app.conversation.mcp.enabled}）时不应有 {@link McpAssistant} bean，
 * 确保 {@code /chat/mcp} 走禁用分支、不试图连接 MCP server。
 */
class McpConfigWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(McpConfig.class));

    @Test
    void disabledByDefault_noMcpAssistantBean() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(McpAssistant.class));
    }

    @Test
    void explicitlyDisabled_noMcpAssistantBean() {
        runner.withPropertyValues("app.conversation.mcp.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(McpAssistant.class));
    }
}
