package com.lrj.platform.asynctask;

import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class AsyncTaskTestDatabase {

    private AsyncTaskTestDatabase() {
    }

    static DriverManagerDataSource migrated(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        SchemaMigrationRunner.migrate(dataSource, SchemaName.ASYNC_TASK);
        return dataSource;
    }
}
