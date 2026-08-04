package com.lrj.platform.migrations;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.migration.JavaMigration;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Programmatic runner shared by the production CLI and integration tests. */
public final class SchemaMigrationRunner {

    private SchemaMigrationRunner() {
    }

    public static Result migrate(String url, String username, String password, SchemaName schema) {
        loadJdbcDriver(url);
        DataSource dataSource = new SimpleDataSource(url, username, password);
        return migrate(dataSource, schema);
    }

    private static void loadJdbcDriver(String url) {
        String driver = url.startsWith("jdbc:mysql:")
                ? "com.mysql.cj.jdbc.Driver"
                : url.startsWith("jdbc:h2:") ? "org.h2.Driver" : null;
        if (driver == null) {
            throw new IllegalArgumentException("unsupported JDBC URL: " + url);
        }
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("JDBC driver is not available for " + url, exception);
        }
    }

    public static Result migrate(DataSource dataSource, SchemaName schema) {
        List<JavaMigration> javaMigrations = new ArrayList<>();
        if (LegacyExpandMigration.supports(schema)) {
            javaMigrations.add(new LegacyExpandMigration(schema));
        }
        if (schema == SchemaName.ANALYTICS_DEMO) {
            javaMigrations.add(new AnalyticsDemoDataMigration());
        }
        if (schema == SchemaName.WORKFLOW) {
            javaMigrations.add(new FlowableSchemaMigration(dataSource));
        }

        FluentConfiguration configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/" + schema.id())
                .table("PLATFORM_SCHEMA_HISTORY")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .failOnMissingLocations(true);
        if (!javaMigrations.isEmpty()) {
            configuration.javaMigrations(javaMigrations.toArray(JavaMigration[]::new));
        }

        Flyway flyway = configuration.load();
        MigrationInfo current = flyway.info().current();
        String initialVersion = current == null ? "none" : current.getVersion().getVersion();
        MigrateResult result = flyway.migrate();
        MigrationInfo target = flyway.info().current();
        return new Result(initialVersion,
                target == null ? "none" : target.getVersion().getVersion(),
                result.migrationsExecuted,
                result.success);
    }

    public record Result(String initialVersion,
                         String targetVersion,
                         int migrationsExecuted,
                         boolean success) {
    }

    private record SimpleDataSource(String url, String username, String password) implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public Connection getConnection(String user, String pass) throws SQLException {
            return DriverManager.getConnection(url, user, pass);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return DriverManager.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            DriverManager.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            DriverManager.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return DriverManager.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger("com.lrj.platform.migrations");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("not a wrapper for " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
