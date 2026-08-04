package com.lrj.platform.workflow;

import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class WorkflowTestDatabase {

    private WorkflowTestDatabase() {
    }

    static DriverManagerDataSource migrated(String name) {
        return migrated(name, "");
    }

    static DriverManagerDataSource migrated(String name, String extraOptions) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1" + extraOptions,
                "sa", "");
        SchemaMigrationRunner.migrate(dataSource, SchemaName.WORKFLOW);
        return dataSource;
    }
}
