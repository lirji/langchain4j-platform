package com.lrj.platform.eval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * eval-service 的启动入口（Spring Boot，端口 8089）。该服务作为外部回归测试客户端，
 * 通过 {@code /eval/**} 接口对被测目标（默认经 edge-gateway）跑用例集、双跑对比与 CI 门禁。
 */
@SpringBootApplication
public class EvalServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvalServiceApplication.class, args);
    }
}
