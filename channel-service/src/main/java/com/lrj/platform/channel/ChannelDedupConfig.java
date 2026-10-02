package com.lrj.platform.channel;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;

import javax.sql.DataSource;

/**
 * 消费侧跨重启去重的独立 DataSource。Kafka 去重或入站 inbox 任一选择 JDBC 时装配，
 * 供 platform-eventbus 的 {@code JdbcProcessedEventStore}（{@code PROCESSED_EVENT} 表）跨重启去重使用。
 *
 * <p>默认（{@code memory}）不创建本 bean、不连库——{@link ChannelServiceApplication} 已排除
 * {@code DataSourceAutoConfiguration}，故 dev/test 零 SQL 依赖照常启动，去重和 inbox 走内存实现。
 * 生产 Kafka 部署下设 {@code CHANNEL_DEDUP_STORE=jdbc} + 数据源 env 即启用。
 */
@Configuration
@Conditional(ChannelDedupConfig.JdbcRequired.class)
public class ChannelDedupConfig {

    /** Kafka 去重或回调 inbox 任一选择 JDBC，都复用同一渠道 DataSource。 */
    static class JdbcRequired extends AnyNestedCondition {
        JdbcRequired() { super(ConfigurationPhase.REGISTER_BEAN); }
        @ConditionalOnProperty(prefix = "platform.eventbus", name = "processed-event-store", havingValue = "jdbc")
        static class Dedup {}
        @ConditionalOnProperty(prefix = "channel.inbox", name = "store", havingValue = "jdbc")
        static class Inbox {}
    }

    @Bean
    public DataSource channelDedupDataSource(
            @Value("${channel.dedup.datasource.url:jdbc:mysql://mysql:3306/channel?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&nullCatalogMeansCurrent=true}") String url,
            @Value("${channel.dedup.datasource.username:channel_app}") String username,
            @Value("${channel.dedup.datasource.password:channel-app-dev}") String password,
            @Value("${channel.dedup.datasource.driver-class-name:com.mysql.cj.jdbc.Driver}") String driver) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(url);
        c.setUsername(username);
        c.setPassword(password);
        c.setDriverClassName(driver);
        c.setMaximumPoolSize(4);
        c.setPoolName("channel-dedup");
        return new HikariDataSource(c);
    }
}
