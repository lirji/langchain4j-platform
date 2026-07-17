package com.lrj.platform.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * workflow-service（Flowable 退款审批 BPMN 引擎 + outbox）的 Spring Boot 启动入口，默认监听 :8082。
 * 开启 {@link org.springframework.scheduling.annotation.EnableScheduling} 以驱动 outbox 中继等定时任务。
 */
@EnableScheduling
@SpringBootApplication
public class WorkflowServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowServiceApplication.class, args);
    }
}
