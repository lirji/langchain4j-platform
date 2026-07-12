package com.lrj.platform.auth;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/** JDBC 数据源装配：仅 {@code AUTH_STORE=jdbc} 时创建（内存模式无 DB 依赖）。 */
@Configuration
@ConditionalOnProperty(name = "app.auth.store", havingValue = "jdbc")
public class AuthJdbcConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.auth.datasource")
    AuthDatasourceProperties authDatasourceProperties() {
        return new AuthDatasourceProperties();
    }

    @Bean
    DataSource authDataSource(AuthDatasourceProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getUrl());
        config.setDriverClassName(properties.getDriverClassName());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setMaximumPoolSize(properties.getMaximumPoolSize());
        config.setPoolName("auth");
        return new HikariDataSource(config);
    }
}
