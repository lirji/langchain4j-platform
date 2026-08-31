package com.lrj.platform.migrations;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaMigrationRunnerTest {

    @Test
    void migratesEveryOwnedSchemaAndIsIdempotent() throws Exception {
        for (SchemaName schema : SchemaName.values()) {
            DataSource dataSource = h2("blank_" + schema.id().replace('-', '_'));
            SchemaMigrationRunner.Result first = SchemaMigrationRunner.migrate(dataSource, schema);
            SchemaMigrationRunner.Result second = SchemaMigrationRunner.migrate(dataSource, schema);

            assertTrue(first.success(), schema.id());
            assertTrue(first.migrationsExecuted() >= 1, schema.id());
            assertTrue(second.success(), schema.id());
            assertEquals(0, second.migrationsExecuted(), schema.id());
        }
    }

    @Test
    void cliConnectionPathLoadsTheJdbcDriverExplicitly() {
        String url = "jdbc:h2:mem:cli_path;MODE=MySQL;DATABASE_TO_UPPER=TRUE;"
                + "DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE,USER";

        SchemaMigrationRunner.Result result = SchemaMigrationRunner.migrate(
                url, "sa", "", SchemaName.AUTH);

        assertTrue(result.success());
        assertEquals("3", result.targetVersion());
    }

    @Test
    void upgradesAndBackfillsLegacyAuthSchemaWithoutDroppingData() throws Exception {
        DataSource dataSource = h2("legacy_auth");
        execute(dataSource, """
                CREATE TABLE USERS (
                  USERNAME VARCHAR(128) PRIMARY KEY,
                  PASSWORD_HASH VARCHAR(256) NOT NULL,
                  TENANT VARCHAR(128) NOT NULL,
                  USER_ID VARCHAR(128) NOT NULL,
                  SCOPES VARCHAR(1024),
                  ROLES VARCHAR(1024),
                  ENABLED BOOLEAN NOT NULL,
                  CREATED_AT BIGINT NOT NULL
                );
                CREATE TABLE ROLES (
                  NAME VARCHAR(128) PRIMARY KEY,
                  SCOPES VARCHAR(1024),
                  DESCRIPTION VARCHAR(256),
                  CREATED_AT BIGINT NOT NULL
                );
                INSERT INTO USERS VALUES ('alice','hash','tenantA','u1','chat','admin,viewer',TRUE,1);
                INSERT INTO ROLES VALUES ('admin','chat,role-admin','admin',1);
                """);

        SchemaMigrationRunner.migrate(dataSource, SchemaName.AUTH);

        assertEquals(2, count(dataSource, "SELECT COUNT(*) FROM USER_ROLE WHERE USERNAME='alice'"));
        assertEquals(3, count(dataSource, "SELECT COUNT(*) FROM ROLE_SCOPE WHERE ROLE_NAME='admin'"));
        assertEquals(2, count(dataSource, "SELECT COUNT(*) FROM ROLE_SCOPE WHERE ROLE_NAME='tax-analyst'"));
        assertEquals(0, count(dataSource, "SELECT VERSION FROM USERS WHERE USERNAME='alice'"));
        assertEquals(1, count(dataSource, "SELECT COUNT(*) FROM USERS WHERE USERNAME='alice'"));
    }

    @Test
    void upgradesLegacyIngestionRowsBeforeAddingUniqueVersionIndex() throws Exception {
        DataSource dataSource = h2("legacy_ingestion");
        execute(dataSource, """
                CREATE TABLE KNOWLEDGE_INGESTION_JOB (
                  TENANT_ID VARCHAR(128) NOT NULL,
                  JOB_ID VARCHAR(128) NOT NULL,
                  IDEMPOTENCY_KEY VARCHAR(512) NOT NULL,
                  USER_ID VARCHAR(256) NOT NULL,
                  SCOPES_JSON TEXT NOT NULL,
                  DEPARTMENT VARCHAR(256),
                  TRACE_ID VARCHAR(256) NOT NULL,
                  DOCUMENT_ID VARCHAR(256) NOT NULL,
                  DOCUMENT_VERSION BIGINT NOT NULL,
                  REVISION BIGINT NOT NULL,
                  SOURCE_BUCKET VARCHAR(256) NOT NULL,
                  SOURCE_OBJECT_KEY VARCHAR(1024) NOT NULL,
                  SOURCE_HASH VARCHAR(256) NOT NULL,
                  SOURCE_CONTENT_TYPE VARCHAR(256) NOT NULL,
                  SOURCE_SIZE BIGINT NOT NULL,
                  STATUS VARCHAR(32) NOT NULL,
                  SINKS_JSON TEXT NOT NULL,
                  REQUIRED_SINKS_JSON TEXT NOT NULL,
                  ERROR_TEXT TEXT,
                  CREATED_AT TIMESTAMP NOT NULL,
                  UPDATED_AT TIMESTAMP NOT NULL,
                  PRIMARY KEY (TENANT_ID, JOB_ID)
                );
                INSERT INTO KNOWLEDGE_INGESTION_JOB VALUES
                  ('tenantA','job1','idem1','u1','[]',NULL,'t1','doc1',1,0,
                   'b','o','h','text/plain',1,'RECEIVED','{}','[]',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
                """);

        SchemaMigrationRunner.migrate(dataSource, SchemaName.KNOWLEDGE_INGESTION);

        assertEquals(1, count(dataSource,
                "SELECT COUNT(*) FROM KNOWLEDGE_INGESTION_JOB WHERE DISPLAY_NAME='doc1'"));
        assertEquals(1, count(dataSource,
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE INDEX_NAME='UK_KNOWLEDGE_INGEST_VERSION'"));
    }

    @Test
    void workflowMigrationOwnsFlowableSchemaVersion() throws Exception {
        DataSource dataSource = h2("flowable_schema");
        SchemaMigrationRunner.Result result = SchemaMigrationRunner.migrate(dataSource, SchemaName.WORKFLOW);

        assertEquals("3", result.targetVersion());
        assertEquals(1, count(dataSource,
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='ACT_RE_PROCDEF'"));
        assertEquals(1, count(dataSource,
                "SELECT COUNT(*) FROM PLATFORM_SCHEMA_HISTORY WHERE \"version\"='3' AND \"success\"=TRUE"));
    }

    private static DataSource h2(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name
                + ";MODE=MySQL;DATABASE_TO_UPPER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE,USER");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void execute(DataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String part : sql.split(";")) {
                if (!part.isBlank()) {
                    statement.execute(part);
                }
            }
        }
    }

    private static int count(DataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }
}
