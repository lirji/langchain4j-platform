package com.lrj.platform.eventbus;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProcessedEventStoreTest：验证 {@link ProcessedEventStore} 的幂等去重语义——{@link InMemoryProcessedEventStore}
 * 与 {@link JdbcProcessedEventStore}（H2 MySQL 模式）首次 markProcessed 返回 true、重复返回 false，
 * 且 JDBC 实现跨 store 实例（模拟重启连同一库）仍能识别已处理事件。
 */
class ProcessedEventStoreTest {

    @Test
    void inMemoryMarksFirstThenDeduplicates() {
        ProcessedEventStore store = new InMemoryProcessedEventStore();

        assertThat(store.markProcessed("evt-1")).isTrue();
        assertThat(store.markProcessed("evt-1")).isFalse();
        assertThat(store.markProcessed("evt-2")).isTrue();
    }

    @Test
    void jdbcMarksFirstThenDeduplicates() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:processed_event_dedup;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ProcessedEventStore store = new JdbcProcessedEventStore(dataSource);

        assertThat(store.markProcessed("evt-1")).isTrue();
        assertThat(store.markProcessed("evt-1")).isFalse();
        assertThat(store.markProcessed("evt-2")).isTrue();
    }

    @Test
    void jdbcDeduplicatesAcrossStoreInstancesOnSameDatabase() {
        String url = "jdbc:h2:mem:processed_event_restart;MODE=MySQL;DB_CLOSE_DELAY=-1";
        ProcessedEventStore first = new JdbcProcessedEventStore(new DriverManagerDataSource(url, "sa", ""));
        assertThat(first.markProcessed("evt-1")).isTrue();

        // 模拟重启：新实例连同一库，仍应识别已处理
        ProcessedEventStore second = new JdbcProcessedEventStore(new DriverManagerDataSource(url, "sa", ""));
        assertThat(second.markProcessed("evt-1")).isFalse();
    }
}
