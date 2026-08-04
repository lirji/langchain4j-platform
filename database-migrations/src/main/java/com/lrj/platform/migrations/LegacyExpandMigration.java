package com.lrj.platform.migrations;

import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/**
 * Versioned expand/backfill step for databases created by the former runtime-DDL stores.
 * New columns are additive and nullable where an old binary must remain able to write rows.
 */
final class LegacyExpandMigration implements JavaMigration {

    private final SchemaName schema;

    LegacyExpandMigration(SchemaName schema) {
        this.schema = schema;
    }

    static boolean supports(SchemaName schema) {
        return switch (schema) {
            case AUTH, ASYNC_TASK, WORKFLOW, KNOWLEDGE_INGESTION, KNOWLEDGE_GRAPH, ORDER -> true;
            default -> false;
        };
    }

    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion("2");
    }

    @Override
    public String getDescription() {
        return "expand and backfill legacy " + schema.id() + " schema";
    }

    @Override
    public Integer getChecksum() {
        return ("legacy-expand-v2:" + schema.id()).hashCode();
    }

    @Override
    public boolean canExecuteInTransaction() {
        return true;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        switch (schema) {
            case AUTH -> migrateAuth(connection);
            case ASYNC_TASK -> migrateAsyncTask(connection);
            case WORKFLOW -> migrateWorkflow(connection);
            case KNOWLEDGE_INGESTION -> migrateKnowledgeIngestion(connection);
            case KNOWLEDGE_GRAPH -> migrateKnowledgeGraph(connection);
            case ORDER -> migrateOrder(connection);
            default -> throw new IllegalStateException("unsupported expand migration: " + schema);
        }
    }

    private static void migrateAuth(Connection connection) throws SQLException {
        addColumn(connection, "USERS", "ROLES", "VARCHAR(1024)");
        addColumn(connection, "USERS", "VERSION", "BIGINT NOT NULL DEFAULT 0");
        addColumn(connection, "ROLES", "VERSION", "BIGINT NOT NULL DEFAULT 0");
        addIndex(connection, "USER_ROLE", "IDX_USER_ROLE_ROLE", "ROLE_NAME", false);
        addIndex(connection, "AUTH_SESSION", "IDX_AUTH_SESSION_USER", "USERNAME", false);
        addIndex(connection, "AUTH_SESSION", "IDX_AUTH_SESSION_EXPIRES", "EXPIRES_AT", false);
        addIndex(connection, "GROUP_ROLE", "IDX_GROUP_ROLE_ROLE", "ROLE_NAME", false);
        addIndex(connection, "USER_GROUP", "IDX_USER_GROUP_GROUP", "GROUP_NAME", false);
        addIndex(connection, "TENANT_ROLE", "IDX_TENANT_ROLE_ROLE", "ROLE_NAME", false);
        backfillCsv(connection, "USERS", "USERNAME", "ROLES",
                "USER_ROLE", "USERNAME", "ROLE_NAME");
        backfillCsv(connection, "ROLES", "NAME", "SCOPES",
                "ROLE_SCOPE", "ROLE_NAME", "SCOPE");
    }

    private static void migrateAsyncTask(Connection connection) throws SQLException {
        addColumn(connection, "ASYNC_TASK", "LEASE_OWNER_ID", "VARCHAR(128)");
        addColumn(connection, "ASYNC_TASK", "LEASE_EXPIRES_AT", "BIGINT");
        addColumn(connection, "ASYNC_TASK", "LEASE_EPOCH", "BIGINT NOT NULL DEFAULT 0");
        addIndex(connection, "ASYNC_TASK", "IDX_ASYNC_TASK_TENANT_CREATED", "TENANT_ID, CREATED_AT", false);
        addIndex(connection, "ASYNC_TASK", "IDX_ASYNC_TASK_FINISHED", "FINISHED_AT", false);
        addIndex(connection, "ASYNC_TASK", "IDX_ASYNC_TASK_LEASE", "STATUS, LEASE_EXPIRES_AT", false);
        addIndex(connection, "ASYNC_TASK", "IDX_ASYNC_TASK_STATUS_CREATED", "STATUS, CREATED_AT", false);
        addIndex(connection, "ASYNC_TASK_EVENT", "UK_ASYNC_TASK_EVENT_KEY", "TASK_ID, EVENT_KEY", true);
        addIndex(connection, "ASYNC_TASK_EVENT", "IDX_ASYNC_TASK_EVENT_CREATED", "CREATED_AT", false);
        expandClaimColumns(connection, "ASYNC_TASK_WEBHOOK_OUTBOX");
        addIndex(connection, "ASYNC_TASK_WEBHOOK_OUTBOX", "IDX_ASYNC_TASK_WEBHOOK_DUE",
                "STATUS, NEXT_ATTEMPT_AT", false);
        addIndex(connection, "ASYNC_TASK_WEBHOOK_OUTBOX", "IDX_ASYNC_TASK_WEBHOOK_CLAIM",
                "STATUS, CLAIMED_UNTIL", false);
        addIndex(connection, "ASYNC_TASK_WEBHOOK_OUTBOX", "IDX_ASYNC_TASK_WEBHOOK_TASK", "TASK_ID", false);
        expandClaimColumns(connection, "ASYNC_TASK_LIFECYCLE_OUTBOX");
        addIndex(connection, "ASYNC_TASK_LIFECYCLE_OUTBOX", "IDX_ASYNC_LIFECYCLE_DUE",
                "STATUS, NEXT_ATTEMPT_AT", false);
        addIndex(connection, "ASYNC_TASK_LIFECYCLE_OUTBOX", "IDX_ASYNC_LIFECYCLE_CLAIM",
                "STATUS, CLAIMED_UNTIL", false);
    }

    private static void migrateWorkflow(Connection connection) throws SQLException {
        for (String table : List.of("WF_OUTBOX", "WF_TERMINAL_EVENT_OUTBOX")) {
            expandClaimColumns(connection, table);
        }
        addIndex(connection, "WF_OUTBOX", "IDX_WF_OUTBOX_DUE", "STATUS, NEXT_ATTEMPT_AT", false);
        addIndex(connection, "WF_OUTBOX", "IDX_WF_OUTBOX_CLAIM", "STATUS, CLAIMED_UNTIL", false);
        addIndex(connection, "WF_TERMINAL_EVENT_OUTBOX", "IDX_WF_EVT_OUTBOX_DUE",
                "STATUS, NEXT_ATTEMPT_AT", false);
        addIndex(connection, "WF_TERMINAL_EVENT_OUTBOX", "IDX_WF_EVT_OUTBOX_CLAIM",
                "STATUS, CLAIMED_UNTIL", false);
        addIndex(connection, "WF_IDEMPOTENCY", "UK_WF_IDEMPOTENCY_INSTANCE", "INSTANCE_ID", true);
    }

    private static void migrateKnowledgeIngestion(Connection connection) throws SQLException {
        addColumn(connection, "KNOWLEDGE_INGESTION_JOB", "DISPLAY_NAME", "VARCHAR(1024)");
        addColumn(connection, "KNOWLEDGE_INGESTION_JOB", "CATEGORY", "VARCHAR(256)");
        addColumn(connection, "KNOWLEDGE_INGESTION_JOB", "NEW_DOCUMENT", "BOOLEAN DEFAULT FALSE");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE KNOWLEDGE_INGESTION_JOB "
                    + "SET DISPLAY_NAME=DOCUMENT_ID WHERE DISPLAY_NAME IS NULL OR DISPLAY_NAME=''");
        }
        addIndex(connection, "KNOWLEDGE_INGESTION_JOB", "UK_KNOWLEDGE_INGEST_IDEM",
                "TENANT_ID, IDEMPOTENCY_KEY", true);
        addIndex(connection, "KNOWLEDGE_INGESTION_JOB", "UK_KNOWLEDGE_INGEST_VERSION",
                "TENANT_ID, DOCUMENT_ID, DOCUMENT_VERSION", true);
    }

    private static void migrateKnowledgeGraph(Connection connection) throws SQLException {
        addIndex(connection, "RAG_GRAPH_TRIPLE", "IDX_RAG_GRAPH_SUBJECT", "TENANT_ID, SUBJECT_KEY", false);
        addIndex(connection, "RAG_GRAPH_TRIPLE", "IDX_RAG_GRAPH_OBJECT", "TENANT_ID, OBJECT_KEY", false);
        addIndex(connection, "RAG_GRAPH_TRIPLE", "IDX_RAG_GRAPH_SOURCE", "TENANT_ID, SOURCE_ID", false);
        addIndex(connection, "RAG_GRAPH_TRIPLE", "IDX_RAG_GRAPH_CATEGORY", "TENANT_ID, CATEGORY", false);
    }

    private static void migrateOrder(Connection connection) throws SQLException {
        addIndex(connection, "orders", "IDX_ORDERS_TENANT", "tenant_id", false);
        addIndex(connection, "customers", "IDX_CUSTOMERS_TENANT", "tenant_id", false);
    }

    private static void expandClaimColumns(Connection connection, String table) throws SQLException {
        addColumn(connection, table, "CLAIMED_BY", "VARCHAR(128)");
        addColumn(connection, table, "CLAIMED_UNTIL", "BIGINT");
    }

    private static void addColumn(Connection connection,
                                  String table,
                                  String column,
                                  String definition) throws SQLException {
        if (columnExists(connection, table, column)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private static void addIndex(Connection connection,
                                 String table,
                                 String index,
                                 String columns,
                                 boolean unique) throws SQLException {
        if (indexExists(connection, table, index)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE " + (unique ? "UNIQUE " : "") + "INDEX " + index
                    + " ON " + table + " (" + columns + ")");
        }
    }

    private static void backfillCsv(Connection connection,
                                    String sourceTable,
                                    String sourceKey,
                                    String sourceCsv,
                                    String targetTable,
                                    String targetKey,
                                    String targetValue) throws SQLException {
        if (!columnExists(connection, sourceTable, sourceCsv)) {
            return;
        }
        String select = "SELECT " + sourceKey + ", " + sourceCsv + " FROM " + sourceTable;
        String insert = "INSERT INTO " + targetTable + " (" + targetKey + ", " + targetValue
                + ", CREATED_AT) VALUES (?, ?, ?)";
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(select);
             PreparedStatement write = connection.prepareStatement(insert)) {
            while (rows.next()) {
                String key = rows.getString(1);
                String csv = rows.getString(2);
                if (csv == null || csv.isBlank()) {
                    continue;
                }
                for (String raw : csv.split(",")) {
                    String value = raw.trim().toLowerCase(Locale.ROOT);
                    if (value.isBlank()) {
                        continue;
                    }
                    write.setString(1, key);
                    write.setString(2, value);
                    write.setLong(3, System.currentTimeMillis());
                    try {
                        write.executeUpdate();
                    } catch (SQLException duplicate) {
                        if (!isConstraintViolation(duplicate)) {
                            throw duplicate;
                        }
                    }
                }
            }
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String tableName : variants(table)) {
            for (String columnName : variants(column)) {
                try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, tableName, columnName)) {
                    if (columns.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean indexExists(Connection connection, String table, String index) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String tableName : variants(table)) {
            try (ResultSet indexes = metadata.getIndexInfo(connection.getCatalog(), null, tableName, false, false)) {
                while (indexes.next()) {
                    String existing = indexes.getString("INDEX_NAME");
                    if (existing != null && existing.equalsIgnoreCase(index)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<String> variants(String identifier) {
        return List.of(identifier, identifier.toUpperCase(Locale.ROOT), identifier.toLowerCase(Locale.ROOT));
    }

    private static boolean isConstraintViolation(SQLException exception) {
        String state = exception.getSQLState();
        return state != null && state.startsWith("23");
    }
}
