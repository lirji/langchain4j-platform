package com.lrj.platform.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * analytics-service 的 Spring Boot 启动入口（:8083），提供 {@code /chat/sql} NL2SQL 分析能力。
 * 排除 {@link DataSourceAutoConfiguration} 自动装配，数据源按需自行配置。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
