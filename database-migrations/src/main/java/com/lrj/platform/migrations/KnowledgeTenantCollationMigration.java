package com.lrj.platform.migrations;

import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * V11：旧任务表可能早于数据库默认 collation 变更，V10 新表不能直接继承默认值后参与等值关联。
 * 只让轮转表的租户字段跟随任务表，保留既有租户比较语义，不重建或转换任务数据。
 */
final class KnowledgeTenantCollationMigration implements JavaMigration {
    /** 独立增量版本，保留已执行 V10 的校验和。 */
    @Override
    public MigrationVersion getVersion() { return MigrationVersion.fromVersion("11"); }

    /** 迁移历史明确记录兼容修复的用途。 */
    @Override
    public String getDescription() { return "align ingestion tenant turn collation with legacy jobs"; }

    /** 迁移内容固定，未来变化必须新增版本。 */
    @Override
    public Integer getChecksum() { return "knowledge-tenant-collation-v11".hashCode(); }

    /** MySQL DDL 会隐式提交，不声称它具有事务回滚能力。 */
    @Override
    public boolean canExecuteInTransaction() { return false; }

    /** 仅独立迁移进程执行 DDL；业务 worker 不获取结构修改权限。 */
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String vendor = connection.getMetaData().getDatabaseProductName();
        // H2 没有 MySQL 的逐列 collation，真实差异由独立 MySQL 测试覆盖。
        if ("H2".equals(vendor)) return;
        if (!"MySQL".equals(vendor)) throw new SQLException("unsupported ingestion collation migration database");
        var job = column(connection, "KNOWLEDGE_INGESTION_JOB");
        var turn = column(connection, "KNOWLEDGE_INGESTION_TENANT_TURN");
        if (job.equals(turn)) return;

        // DDL 标识符不可绑定参数；值仅来自当前库元数据且经严格标识符检查，表列名固定。
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE KNOWLEDGE_INGESTION_TENANT_TURN "
                    + "MODIFY COLUMN TENANT_ID VARCHAR(128) CHARACTER SET " + job.charset()
                    + " COLLATE " + job.collation() + " NOT NULL COMMENT '与任务库一致的租户标识'");
        }
    }

    private static Column column(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT CHARACTER_SET_NAME,COLLATION_NAME,COLUMN_TYPE "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='TENANT_ID'")) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("ingestion tenant column is missing");
                String charset = result.getString(1), collation = result.getString(2), type = result.getString(3);
                if (!"varchar(128)".equalsIgnoreCase(type) || !identifier(charset) || !identifier(collation)) {
                    throw new SQLException("unsupported ingestion tenant column definition");
                }
                return new Column(charset, collation);
            }
        }
    }

    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9_]+"); }
    private record Column(String charset, String collation) { }
}
