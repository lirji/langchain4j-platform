package com.lrj.platform.conversation.memory.profile;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** 仅在画像启用且选择 JDBC 时创建连接池，关闭功能不会隐式依赖数据库。 */
@Configuration
@Conditional(ProfileJdbcConfig.JdbcEnabled.class)
@EnableConfigurationProperties(ProfileJdbcProperties.class)
public class ProfileJdbcConfig {
    static class JdbcEnabled extends AllNestedConditions {
        JdbcEnabled() { super(ConfigurationPhase.PARSE_CONFIGURATION); }

        @ConditionalOnProperty(prefix = "app.conversation.memory.profile", name = "enabled", havingValue = "true")
        static class Enabled {}

        @ConditionalOnProperty(prefix = "app.conversation.memory.profile", name = "store",
                havingValue = "jdbc", matchIfMissing = true)
        static class Jdbc {}
    }

    /** Spring 负责关闭池；应用数据源只持 DML 权限，不运行 Flyway 或其他 schema 初始化器。 */
    @Bean(destroyMethod = "close")
    HikariDataSource profileDataSource(ProfileJdbcProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.url());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setMaximumPoolSize(properties.maximumPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(properties.connectionTimeoutMs());
        config.setPoolName("conversation-profile");
        return new HikariDataSource(config);
    }

    /** 构造时校验独立迁移已完成，避免服务启动后才发现画像表缺失。 */
    @Bean
    UserProfileStore jdbcUserProfileStore(HikariDataSource profileDataSource,
            @Value("${app.conversation.memory.profile.max-items:50}") int maxItems) {
        return new JdbcUserProfileStore(profileDataSource, maxItems);
    }
}
