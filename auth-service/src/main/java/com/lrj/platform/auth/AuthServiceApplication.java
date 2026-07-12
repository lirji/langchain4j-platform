package com.lrj.platform.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 登录服务：账号密码 → 会话访问 JWT（前端内存持有）+ 刷新令牌（httpOnly cookie，轮转续期）。
 *
 * <p>排除 {@link DataSourceAutoConfiguration}：账号存储默认内存（无 DB），仅 {@code AUTH_STORE=jdbc}
 * 时由 {@code AuthJdbcConfig} 显式建数据源。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
