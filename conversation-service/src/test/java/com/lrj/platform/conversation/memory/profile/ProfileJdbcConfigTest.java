package com.lrj.platform.conversation.memory.profile;

import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 配置验收：持久化为默认，功能关闭不连接数据库，缺迁移或错误配置直接失败。 */
class ProfileJdbcConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProfileJdbcConfig.class);

    @Test
    void disabledDoesNotRequireDatabaseSettings() {
        runner.withPropertyValues("app.conversation.memory.profile.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(HikariDataSource.class);
            assertThat(context).doesNotHaveBean(UserProfileStore.class);
        });
    }

    @Test
    void explicitDevelopmentMemoryDoesNotCreateJdbcPool() {
        runner.withPropertyValues("app.conversation.memory.profile.enabled=true",
                "app.conversation.memory.profile.store=in-memory").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(HikariDataSource.class);
        });
    }

    @Test
    void defaultStoreIsJdbcAndOwnsPoolLifecycle() {
        String url = database(true);
        HikariDataSource[] pool = new HikariDataSource[1];
        configured(url).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(UserProfileStore.class);
            assertThat(context.getBean(UserProfileStore.class)).isInstanceOf(JdbcUserProfileStore.class);
            pool[0] = context.getBean(HikariDataSource.class);
            assertThat(pool[0].getMaximumPoolSize()).isEqualTo(4);
            assertThat(pool[0].getConnectionTimeout()).isEqualTo(3000);
        });
        assertThat(pool[0].isClosed()).isTrue();
    }

    @Test
    void missingSchemaFailsWithoutFallingBackToMemory() {
        configured(database(false)).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingDatasourceAndInvalidPoolCapacityFailClosed() {
        runner.withPropertyValues("app.conversation.memory.profile.enabled=true")
                .run(context -> assertThat(context).hasFailed());
        configured(database(true)).withPropertyValues(
                "app.conversation.memory.profile.datasource.maximum-pool-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner configured(String url) {
        return runner.withPropertyValues("app.conversation.memory.profile.enabled=true",
                "app.conversation.memory.profile.datasource.url=" + url,
                "app.conversation.memory.profile.datasource.username=sa");
    }

    private static String database(boolean migrate) {
        String url = "jdbc:h2:mem:config_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        if (migrate) SchemaMigrationRunner.migrate(new DriverManagerDataSource(url, "sa", ""), SchemaName.CONVERSATION);
        return url;
    }
}
