package com.lrj.platform.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Spring Cloud Config Server 主类。
 *
 * <p>集中化下发各服务的非密文配置。默认走 <b>native</b> 后端（读 classpath/本地目录，
 * 便于 dev/无 git 环境），可经 {@code spring.profiles.active=git} +
 * {@code SPRING_CLOUD_CONFIG_SERVER_GIT_URI} 切到 git 后端。
 *
 * <p>各业务服务用 {@code spring.config.import=optional:configserver:...} 接入，
 * server 不可达时不阻断启动，各服务 {@code ${ENV:default}} 兜底继续生效。
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
