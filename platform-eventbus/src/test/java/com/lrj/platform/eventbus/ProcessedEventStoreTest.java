package com.lrj.platform.eventbus;

import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProcessedEventStoreTest：验证 {@link ProcessedEventStore} 的幂等去重语义——{@link InMemoryProcessedEventStore}
 * 与 {@link JdbcProcessedEventStore}（H2 MySQL 模式）首次 markProcessed 返回 true、重复返回 false，
 * JDBC 实现跨 store 实例（模拟重启连同一库）仍能识别已处理事件，内存实现的去重窗口有界（满后淘汰最早），
 * 以及两种实现的 releaseClaim 都能归还抢占让消息重新被处理。
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
        SchemaMigrationRunner.migrate(dataSource, SchemaName.CHANNEL);
        ProcessedEventStore store = new JdbcProcessedEventStore(dataSource);

        assertThat(store.markProcessed("evt-1")).isTrue();
        assertThat(store.markProcessed("evt-1")).isFalse();
        assertThat(store.markProcessed("evt-2")).isTrue();
    }

    @Test
    void inMemoryBoundsDeduplicationWindow() {
        // 入站回调持续产生新 id，无界 map 等于内存泄漏；窗口满后按最早插入淘汰
        ProcessedEventStore store = new InMemoryProcessedEventStore(2);

        assertThat(store.markProcessed("evt-1")).isTrue();
        assertThat(store.markProcessed("evt-2")).isTrue();
        assertThat(store.markProcessed("evt-3")).isTrue();

        assertThat(store.isProcessed("evt-1")).as("最早的被淘汰").isFalse();
        assertThat(store.isProcessed("evt-2")).isTrue();
        assertThat(store.isProcessed("evt-3")).isTrue();
    }

    @Test
    void releaseClaimAllowsReprocessing() {
        ProcessedEventStore store = new InMemoryProcessedEventStore();
        assertThat(store.markProcessed("evt-1")).isTrue();

        store.releaseClaim("evt-1");

        assertThat(store.isProcessed("evt-1")).isFalse();
        assertThat(store.markProcessed("evt-1")).as("归还后可再次抢占").isTrue();
    }

    @Test
    void jdbcReleaseClaimAllowsReprocessing() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:processed_event_release;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        SchemaMigrationRunner.migrate(dataSource, SchemaName.CHANNEL);
        ProcessedEventStore store = new JdbcProcessedEventStore(dataSource);
        assertThat(store.markProcessed("evt-1")).isTrue();

        store.releaseClaim("evt-1");

        assertThat(store.isProcessed("evt-1")).isFalse();
        assertThat(store.markProcessed("evt-1")).isTrue();
        // 归还不存在的 id 是 no-op，不报错
        store.releaseClaim("evt-missing");
    }

    @Test
    void jdbcDeduplicatesAcrossStoreInstancesOnSameDatabase() {
        String url = "jdbc:h2:mem:processed_event_restart;MODE=MySQL;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        SchemaMigrationRunner.migrate(dataSource, SchemaName.CHANNEL);
        ProcessedEventStore first = new JdbcProcessedEventStore(dataSource);
        assertThat(first.markProcessed("evt-1")).isTrue();

        // 模拟重启：新实例连同一库，仍应识别已处理
        ProcessedEventStore second = new JdbcProcessedEventStore(dataSource);
        assertThat(second.markProcessed("evt-1")).isFalse();
    }
}
