package com.lrj.platform.knowledge.operations;

import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;

/** 默认隔离H2；真实MySQL由专用脚本提供临时库和分离的迁移/DML账户。 */
final class OperationsTestDatabase {
    static DataSource create() {
        String url = System.getenv().getOrDefault("OPERATIONS_TEST_DB_URL",
                "jdbc:h2:mem:operations-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        String user = System.getenv().getOrDefault("OPERATIONS_TEST_DB_USER", "sa");
        String password = System.getenv().getOrDefault("OPERATIONS_TEST_DB_PASSWORD", "");
        SchemaMigrationRunner.migrate(new DriverManagerDataSource(url,
                System.getenv().getOrDefault("OPERATIONS_TEST_MIGRATION_DB_USER", user),
                System.getenv().getOrDefault("OPERATIONS_TEST_MIGRATION_DB_PASSWORD", password)), SchemaName.KNOWLEDGE_INGESTION);
        return new DriverManagerDataSource(url, user, password);
    }
}
