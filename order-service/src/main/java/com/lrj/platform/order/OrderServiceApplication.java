package com.lrj.platform.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * order-service（:8093）—— 按订单号只读查订单的下游业务服务，供深度 Agent 的 {@code order_query} 动作调用。
 *
 * <p>单库、只读，标准 {@code spring.datasource.*} 由 Boot 自动装配 {@link org.springframework.jdbc.core.JdbcTemplate}
 * （无需像 analytics/async-task 那样手搓多池 + exclude DataSourceAutoConfiguration）。不调 LLM，
 * 故不依赖 langchain4j / platform-gateway-client / platform-metering。
 * 入站鉴权与租户还原由 platform-security 自注册，controller/store 只读 {@code TenantContext} 做隔离。
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
