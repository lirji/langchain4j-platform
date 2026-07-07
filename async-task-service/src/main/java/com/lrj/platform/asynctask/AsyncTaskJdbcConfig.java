package com.lrj.platform.asynctask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.eventbus.EventPublisher;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "app.async-task.store", havingValue = "jdbc")
public class AsyncTaskJdbcConfig {

    /** async-task 数据源事务管理器：供 {@link JdbcAsyncTaskStore} 把「终态更新 + 生命周期事件 outbox 写」原子提交（A1）。 */
    @Bean
    PlatformTransactionManager asyncTaskTransactionManager(DataSource asyncTaskDataSource) {
        return new DataSourceTransactionManager(asyncTaskDataSource);
    }

    /** 生命周期事件事务性 outbox：仅 transport=kafka 时创建（A1，store=jdbc 由本类 @ConditionalOnProperty 保证）。 */
    @Bean
    @ConditionalOnProperty(prefix = "app.async-task.webhook", name = "transport", havingValue = "kafka")
    AsyncTaskLifecycleOutbox asyncTaskLifecycleOutbox(DataSource asyncTaskDataSource) {
        return new AsyncTaskLifecycleOutbox(asyncTaskDataSource);
    }

    /** 生命周期事件 Kafka relay：仅 store=jdbc + transport=kafka 时创建（A1）。 */
    @Bean
    @ConditionalOnProperty(prefix = "app.async-task.webhook", name = "transport", havingValue = "kafka")
    AsyncTaskLifecycleRelay asyncTaskLifecycleRelay(AsyncTaskLifecycleOutbox asyncTaskLifecycleOutbox,
                                                    EventPublisher eventPublisher,
                                                    ObjectMapper mapper,
                                                    AsyncTaskWebhookProperties props) {
        return new AsyncTaskLifecycleRelay(asyncTaskLifecycleOutbox, eventPublisher, mapper, props);
    }

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
