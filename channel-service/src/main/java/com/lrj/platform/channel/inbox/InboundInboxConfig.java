package com.lrj.platform.channel.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 复用渠道数据库；独立 inbox 只保存规范化消息，不保存 JWT 或平台凭据。 */
@Configuration
@EnableScheduling
public class InboundInboxConfig {
    @Bean
    @ConditionalOnProperty(prefix = "channel.inbox", name = "store", havingValue = "jdbc")
    InboundInboxStore jdbcInboundInboxStore(DataSource dataSource) {
        return new JdbcInboundInboxStore(dataSource);
    }

    @Bean
    @ConditionalOnProperty(prefix = "channel.inbox", name = "store", havingValue = "memory", matchIfMissing = true)
    InboundInboxStore memoryInboundInboxStore(@Value("${channel.inbox.memory-capacity:10000}") int capacity) {
        return new InMemoryInboundInboxStore(capacity);
    }

    /** 队列有界且槽位在领取前获取，不能将数据库积压搬成无界进程堆积。 */
    @Bean(destroyMethod = "shutdown")
    ThreadPoolExecutor inboxExecutor(@Value("${channel.inbox.concurrency:4}") int concurrency) {
        if (concurrency < 1 || concurrency > 64) throw new IllegalArgumentException("invalid inbox concurrency");
        return new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(concurrency), r -> {
                    Thread t = new Thread(r, "channel-inbox"); t.setDaemon(true); return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    InboundInbox inboundInbox(InboundInboxStore store, ObjectMapper json, ThreadPoolExecutor inboxExecutor,
                             @Value("${channel.inbox.concurrency:4}") int concurrency,
                             @Value("${channel.inbox.max-attempts:5}") int attempts,
                             @Value("${channel.inbox.lease-millis:60000}") long lease,
                             @Value("${channel.inbox.retry-millis:5000}") long retry,
                             @Value("${channel.inbox.execution-millis:180000}") long execution) {
        return new InboundInbox(store, json, inboxExecutor, Clock.systemUTC(), concurrency, attempts, lease, retry, execution);
    }

    @Bean
    Retention inboxRetention(InboundInboxStore store,
                             @Value("${channel.inbox.success-retention-millis:0}") long retention) {
        if (retention < 0) throw new IllegalArgumentException("negative inbox retention");
        return new Retention(store, retention);
    }

    /** 默认不删除；保留窗口由业务明确配置，只清理 SUCCEEDED，不删除未决/隔离消息。 */
    public record Retention(InboundInboxStore store, long retentionMillis) {
        @Scheduled(fixedDelayString = "${channel.inbox.cleanup-millis:60000}")
        public void cleanup() {
            if (retentionMillis > 0) store.cleanupSucceeded(System.currentTimeMillis() - retentionMillis, 100);
        }
    }
}
