package com.lrj.platform.auth;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

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

    /**
     * auth 数据源专用事务管理器。{@code RbacMutationExecutor}（jdbc 变体）用它把用户/角色/关系表/
     * refresh session 的复合写包进单个事务；各 {@code Jdbc*Store} 的 JdbcTemplate 共用同一 DataSource，
     * 因而自动加入该事务，实现跨 store 原子提交/回滚。
     */
    @Bean
    PlatformTransactionManager authTransactionManager(DataSource authDataSource) {
        return new DataSourceTransactionManager(authDataSource);
    }
}
