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
        String integrationUrl = System.getenv("WORKFLOW_RECEIPT_TEST_DB_URL");
        if (integrationUrl != null && !integrationUrl.matches(
                "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/lc4j_workflow_it_[a-z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("receipt integration requires an isolated lc4j_workflow_it database");
        DriverManagerDataSource dataSource = integrationUrl == null
                ? new DriverManagerDataSource("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1" + extraOptions, "sa", "")
                : new DriverManagerDataSource(integrationUrl, System.getenv("WORKFLOW_RECEIPT_TEST_DB_USER"),
                    System.getenv("WORKFLOW_RECEIPT_TEST_DB_PASSWORD"));
        SchemaMigrationRunner.migrate(dataSource, SchemaName.WORKFLOW);
        return dataSource;
    }
}
