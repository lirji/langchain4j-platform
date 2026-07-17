package com.lrj.platform.vision;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * vision-service（{@code /vision/**} 图像描述）的 Spring Boot 启动入口，默认监听 :8090。
 */
@SpringBootApplication
public class VisionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VisionServiceApplication.class, args);
    }
}
