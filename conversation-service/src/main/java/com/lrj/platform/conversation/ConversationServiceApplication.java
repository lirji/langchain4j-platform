package com.lrj.platform.conversation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * conversation-service 的 Spring Boot 启动入口（:8081）：承载 {@code /chat} 及其流式、意图路由、视觉、MCP、
 * 抽取、长期画像等对话能力，只信任 edge-gateway 换发的内部 JWT。
 */
// 画像数据源由 ProfileJdbcConfig 条件创建，关闭画像时不自动创建数据库连接。
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class ConversationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConversationServiceApplication.class, args);
    }
}
