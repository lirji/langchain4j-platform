package com.lrj.platform.knowledge.ingest.sync;

import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.net.URI;
import java.time.*;
import java.util.UUID;

/** 使用隔离库验证真实事务；可切换到仅有DML权限的MySQL账号。 */
public final class SyncTestSupport {
    public static DataSource database() {
        String url = System.getenv("HTTP_SYNC_TEST_DB_URL");
        if (url != null && !url.contains("/lc4j_sync_it_")) throw new IllegalArgumentException("isolated test schema required");
        if (url == null) url = "jdbc:h2:mem:sync-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        String user = System.getenv().getOrDefault("HTTP_SYNC_TEST_DB_USER", "sa");
        String password = System.getenv().getOrDefault("HTTP_SYNC_TEST_DB_PASSWORD", "");
        SchemaMigrationRunner.migrate(new DriverManagerDataSource(url,
                System.getenv().getOrDefault("HTTP_SYNC_TEST_MIGRATION_DB_USER", user),
                System.getenv().getOrDefault("HTTP_SYNC_TEST_MIGRATION_DB_PASSWORD", password)), SchemaName.KNOWLEDGE_INGESTION);
        return new DriverManagerDataSource(url, user, password);
    }
    static KnowledgeSyncProperties.Source source(String tenant) {
        return new KnowledgeSyncProperties.Source("faq", tenant, "sync-user", "support", URI.create("https://example.invalid/feed"), "secret", false);
    }
    public static final class MutableClock extends Clock {
        private long now = 1_800_000_000_000L;
        public void advance() { now += 61_000; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }
}
