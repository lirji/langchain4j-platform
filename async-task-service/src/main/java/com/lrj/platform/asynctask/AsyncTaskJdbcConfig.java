package com.lrj.platform.asynctask;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "app.async-task.store", havingValue = "jdbc")
public class AsyncTaskJdbcConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.async-task.datasource")
    AsyncTaskDatasourceProperties asyncTaskDatasourceProperties() {
        return new AsyncTaskDatasourceProperties();
    }

    @Bean
    DataSource asyncTaskDataSource(AsyncTaskDatasourceProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getUrl());
        config.setDriverClassName(properties.getDriverClassName());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setMaximumPoolSize(properties.getMaximumPoolSize());
        config.setPoolName("async-task");
        return new HikariDataSource(config);
    }
}
