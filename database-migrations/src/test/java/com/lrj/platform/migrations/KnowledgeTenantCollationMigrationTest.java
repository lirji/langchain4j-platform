package com.lrj.platform.migrations;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实 MySQL 复现历史任务表与新轮转表的排序规则冲突；仅允许专用随机测试库。 */
class KnowledgeTenantCollationMigrationTest {
    @Test
    void alignsTurnColumnWithLegacyJobWithoutChangingRowsAndIsIdempotent() throws Exception {
        String url = System.getenv("COLLATION_TEST_DB_URL");
        assumeTrue(url != null, "需要独立 MySQL 测试库");
        assertTrue(url.matches("jdbc:mysql://[^/]+/lc4j_collation_it_[a-f0-9]+\\?.*"));
        String expected = System.getenv("COLLATION_TEST_JOB_COLLATION");
        assertTrue(Set.of("utf8mb4_unicode_ci", "utf8mb4_0900_ai_ci").contains(expected));
        String other = expected.equals("utf8mb4_unicode_ci") ? "utf8mb4_0900_ai_ci" : "utf8mb4_unicode_ci";
        var source = new MysqlDataSource();
        source.setURL(url);
        source.setUser(System.getenv("COLLATION_TEST_DB_USER"));
        source.setPassword(System.getenv("COLLATION_TEST_DB_PASSWORD"));

        // 固定到实际有问题的 V10，保留旧版本迁移校验和，不通过修改历史 SQL 掩盖问题。
        Flyway.configure().dataSource(source).locations("classpath:db/migration/knowledge-ingestion")
                .javaMigrations(new LegacyExpandMigration(SchemaName.KNOWLEDGE_INGESTION))
                .table("PLATFORM_SCHEMA_HISTORY").target("10").cleanDisabled(true).load().migrate();
        try (Connection connection = source.getConnection()) {
            execute(connection, "ALTER TABLE KNOWLEDGE_INGESTION_JOB MODIFY TENANT_ID VARCHAR(128) "
                    + "CHARACTER SET utf8mb4 COLLATE " + expected + " NOT NULL COMMENT '历史任务租户标识'");
            execute(connection, "ALTER TABLE KNOWLEDGE_INGESTION_TENANT_TURN MODIFY TENANT_ID VARCHAR(128) "
                    + "CHARACTER SET utf8mb4 COLLATE " + other + " NOT NULL COMMENT '租户轮转标识'");
            execute(connection, """
                    INSERT INTO KNOWLEDGE_INGESTION_JOB
                    (TENANT_ID,JOB_ID,IDEMPOTENCY_KEY,USER_ID,SCOPES_JSON,TRACE_ID,DOCUMENT_ID,DISPLAY_NAME,
                     DOCUMENT_VERSION,NEW_DOCUMENT,REVISION,SOURCE_BUCKET,SOURCE_OBJECT_KEY,SOURCE_HASH,
                     SOURCE_CONTENT_TYPE,SOURCE_SIZE,STATUS,SINKS_JSON,REQUIRED_SINKS_JSON,CREATED_AT,UPDATED_AT)
                    VALUES ('TenantA','job1','idem1','u1','[]','trace1','doc1','文档',1,TRUE,0,
                            'bucket','object','hash','application/pdf',1,'RECEIVED','{}','[]',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
            execute(connection, "INSERT INTO KNOWLEDGE_INGESTION_TENANT_TURN VALUES ('tenanta',42)");
            SQLException failure = assertThrows(SQLException.class, () -> joinedGrant(connection));
            assertEquals(1267, failure.getErrorCode(), "必须先复现 MySQL 排序规则冲突");
        }

        var result = SchemaMigrationRunner.migrate(source, SchemaName.KNOWLEDGE_INGESTION);
        assertTrue(result.success());
        assertEquals("12", result.targetVersion());
        assertEquals(2, result.migrationsExecuted());
        try (Connection connection = source.getConnection()) {
            assertEquals(42, joinedGrant(connection));
            assertEquals(expected, collation(connection, "KNOWLEDGE_INGESTION_JOB"));
            assertEquals(expected, collation(connection, "KNOWLEDGE_INGESTION_TENANT_TURN"));
            assertEquals(expected, collation(connection, "KNOWLEDGE_INGESTION_CONTENT_GUARD"));
            execute(connection, "INSERT INTO KNOWLEDGE_INGESTION_CONTENT_GUARD VALUES ('TenantA','hash',7)");
            // 同任务表的大小写比较语义也保护相同内容的并发提交。
            assertThrows(SQLException.class, () -> execute(connection,
                    "INSERT INTO KNOWLEDGE_INGESTION_CONTENT_GUARD VALUES ('tenanta','hash',0)"));
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT TENANT_ID,STATUS,DISPLAY_NAME FROM KNOWLEDGE_INGESTION_JOB")) {
                assertTrue(rows.next());
                assertEquals("TenantA", rows.getString(1));
                assertEquals("RECEIVED", rows.getString(2));
                assertEquals("文档", rows.getString(3));
                assertFalse(rows.next());
            }
        }
        assertEquals(0, SchemaMigrationRunner.migrate(source, SchemaName.KNOWLEDGE_INGESTION).migrationsExecuted());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static long joinedGrant(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(
                "SELECT t.LAST_GRANTED FROM KNOWLEDGE_INGESTION_JOB j "
                        + "LEFT JOIN KNOWLEDGE_INGESTION_TENANT_TURN t ON t.TENANT_ID=j.TENANT_ID")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static String collation(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT COLLATION_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='TENANT_ID'")) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) { assertTrue(result.next()); return result.getString(1); }
        }
    }
}
