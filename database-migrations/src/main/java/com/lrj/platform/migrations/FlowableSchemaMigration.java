package com.lrj.platform.migrations;

import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;

import javax.sql.DataSource;

/** Pins Flowable 7.1 schema creation/upgrade to Flyway version 3 instead of business-app startup. */
final class FlowableSchemaMigration implements JavaMigration {

    private final DataSource dataSource;

    FlowableSchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion("3");
    }

    @Override
    public String getDescription() {
        return "flowable 7.1 schema";
    }

    @Override
    public Integer getChecksum() {
        return "flowable-schema-v3:7.1.0".hashCode();
    }

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) {
        StandaloneProcessEngineConfiguration configuration =
                new StandaloneProcessEngineConfiguration();
        configuration.setDataSource(dataSource);
        try {
            if ("H2".equalsIgnoreCase(context.getConnection().getMetaData().getDatabaseProductName())) {
                // Integration tests run H2 in MySQL compatibility mode; use the same vendor DDL as production.
                configuration.setDatabaseType("mysql");
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("cannot detect workflow migration database", exception);
        }
        configuration.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        configuration.setAsyncExecutorActivate(false);
        ProcessEngine engine = configuration.buildProcessEngine();
        engine.close();
    }
}
