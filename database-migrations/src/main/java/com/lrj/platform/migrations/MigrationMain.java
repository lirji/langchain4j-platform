package com.lrj.platform.migrations;

/** CLI entrypoint used only by the controlled migration Job/Compose one-shot service. */
public final class MigrationMain {

    private MigrationMain() {
    }

    public static void main(String[] args) {
        String url = required("MIGRATION_DB_URL");
        String username = required("MIGRATION_DB_USER");
        String password = required("MIGRATION_DB_PASSWORD");
        SchemaName schema = SchemaName.parse(required("MIGRATION_SCHEMA"));

        SchemaMigrationRunner.Result result = SchemaMigrationRunner.migrate(
                url, username, password, schema);
        System.out.printf("schema=%s initial=%s target=%s migrations=%d success=%s%n",
                schema.id(), result.initialVersion(), result.targetVersion(),
                result.migrationsExecuted(), result.success());
        if (!result.success()) {
            throw new IllegalStateException("database migration did not complete successfully");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
