package com.lrj.platform.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * agent-service 的 Spring Boot 启动入口（默认端口 8085）。承载 {@code /agent/run} ReAct、
 * {@code /agent/dag/**} 多 Agent DAG，以及 chain/vote/reflexive 等轻量编排与异步任务中心。
 */
@SpringBootApplication
public class AgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
