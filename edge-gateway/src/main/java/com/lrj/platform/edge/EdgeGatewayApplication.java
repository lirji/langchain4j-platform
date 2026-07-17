package com.lrj.platform.edge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * edge-gateway（Spring Cloud Gateway/WebFlux，平台唯一对外入口）的 Spring Boot 启动入口，默认监听 :8080。
 * 校验入站凭证后签发短时内部 JWT 并按路径路由到各下游服务。
 */
@SpringBootApplication
public class EdgeGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(EdgeGatewayApplication.class, args);
    }
}
