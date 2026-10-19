package com.lrj.platform.migrations;

import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;

import java.sql.Connection;
import java.sql.SQLException;

/** V12：内容去重只扩展守卫和查询索引，历史重复文档保持原样，不改写任务及引用。 */
final class KnowledgeContentDedupMigration implements JavaMigration {
    @Override
    public MigrationVersion getVersion() { return MigrationVersion.fromVersion("12"); }
    @Override
    public String getDescription() { return "ingestion content deduplication guard"; }
    @Override
    public Integer getChecksum() { return "knowledge-content-dedup-v12".hashCode(); }
    @Override
    public boolean canExecuteInTransaction() { return false; }

    /** 租户比较规则必须跟随历史任务表，防止默认 collation 变更使并发守卫失效。 */
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String tenantCollation = tenantCollation(connection);
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS KNOWLEDGE_INGESTION_CONTENT_GUARD ("
                    + "TENANT_ID VARCHAR(128)" + tenantCollation + " NOT NULL COMMENT '与任务库比较规则一致的租户标识',"
                    + "SOURCE_HASH VARCHAR(256) NOT NULL COMMENT '服务端流式计算的原文件 SHA-256 摘要',"
                    + "GENERATION BIGINT NOT NULL DEFAULT 0 COMMENT '此租户内容新增任务的单调序号，保护锁外去重检查',"
                    + "PRIMARY KEY (TENANT_ID,SOURCE_HASH)) COMMENT='知识库内容去重事务守卫，不保存或删除文档副本'");
        }
        // MySQL DDL 隐式提交；部分执行后的重跑只补缺失索引。
        boolean indexed = false;
        try (var indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null,
                "KNOWLEDGE_INGESTION_JOB", false, false)) {
            while (indexes.next()) if ("IDX_INGESTION_CONTENT".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) indexed = true;
        }
        if (!indexed) try (var statement = connection.createStatement()) {
            statement.execute("CREATE INDEX IDX_INGESTION_CONTENT ON KNOWLEDGE_INGESTION_JOB "
                    + "(TENANT_ID,SOURCE_HASH,DELETED,JOB_ID)");
        }
    }

    private static String tenantCollation(Connection connection) throws SQLException {
        String vendor = connection.getMetaData().getDatabaseProductName();
        if ("H2".equals(vendor)) return "";
        if (!"MySQL".equals(vendor)) throw new SQLException("unsupported ingestion deduplication database");
        try (var statement = connection.prepareStatement("SELECT CHARACTER_SET_NAME,COLLATION_NAME "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                + "AND TABLE_NAME='KNOWLEDGE_INGESTION_JOB' AND COLUMN_NAME='TENANT_ID'");
             var result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("ingestion tenant column is missing");
            String charset = result.getString(1), collation = result.getString(2);
            if (charset == null || collation == null || !charset.matches("[A-Za-z0-9_]+")
                    || !collation.matches("[A-Za-z0-9_]+")) throw new SQLException("invalid ingestion tenant collation");
            return " CHARACTER SET " + charset + " COLLATE " + collation;
        }
    }
}
