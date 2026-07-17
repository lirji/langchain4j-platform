package com.lrj.platform.interop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * interop-service（A2A / MCP 互操作服务，默认 :8088）的 Spring Boot 启动入口。
 */
@SpringBootApplication
public class InteropServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InteropServiceApplication.class, args);
    }
}
