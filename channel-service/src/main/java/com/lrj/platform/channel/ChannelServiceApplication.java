package com.lrj.platform.channel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 排除 {@link DataSourceAutoConfiguration}：channel-service 默认无 SQL 源（去重默认走内存）。
 * 仅当 {@code platform.eventbus.processed-event-store=jdbc}（Kafka 生产部署跨重启去重）时，
 * 由 {@link ChannelDedupConfig} 手动建独立 DataSource；否则不连库，保持零依赖 dev/test。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class ChannelServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChannelServiceApplication.class, args);
    }
}
