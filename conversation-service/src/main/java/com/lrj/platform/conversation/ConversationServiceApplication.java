package com.lrj.platform.conversation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * conversation-service 的 Spring Boot 启动入口（:8081）：承载 {@code /chat} 及其流式、意图路由、视觉、MCP、
 * 抽取、长期画像等对话能力，只信任 edge-gateway 换发的内部 JWT。
 */
@SpringBootApplication
public class ConversationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConversationServiceApplication.class, args);
    }
}
