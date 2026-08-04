package com.lrj.platform.analytics;

import com.lrj.platform.audit.AuditLogger;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * NL2SQL 装配。整体 {@code @ConditionalOnProperty(app.nl2sql.enabled)}，默认关 = 零开销、不影响现有启动。
 *
 * <p>只注册一个 {@link DataSource} bean（只读，用于 schema 内省）。
 * 只读执行用的第二个连接池（L1：只读 DB 账号）在 {@link #nl2sqlReadOnlyJdbc} 里就地构建，
 * <strong>不注册为 bean</strong> —— 避免出现两个 DataSource bean 引起注入歧义，也对齐
 * {@code SqlQueryTool} "不进 Spring 容器" 的同款隔离思路。
 */
@Configuration
@ConditionalOnProperty(name = "app.nl2sql.enabled", havingValue = "true")
@EnableConfigurationProperties(Nl2SqlProperties.class)
public class Nl2SqlConfig {

    /** schema 内省也使用只读账号；建表/种子只允许独立 migration runner 执行。 */
    @Bean
    public DataSource nl2sqlAdminDataSource(Nl2SqlProperties props) {
        Nl2SqlProperties.Datasource d = props.getDatasource();
        return pool(readOnlyUrl(d), d.getReadonlyUsername(), d.getReadonlyPassword(), true, "nl2sql-schema-ro");
    }

    /**
     * 只读执行的 JdbcTemplate。绑定只读 DB 账号（L1）+ statement 超时（L5）。
     * 入参 {@code nl2sqlAdminDataSource} 用于共享只读 schema 连接的启动校验顺序。
     */
    @Bean
    public JdbcTemplate nl2sqlReadOnlyJdbc(Nl2SqlProperties props, DataSource nl2sqlAdminDataSource) {
        Nl2SqlProperties.Datasource d = props.getDatasource();
        HikariDataSource readOnly = pool(readOnlyUrl(d), d.getReadonlyUsername(), d.getReadonlyPassword(), true, "nl2sql-ro");
        JdbcTemplate jdbc = new JdbcTemplate(readOnly);
        jdbc.setQueryTimeout(props.getQueryTimeoutSeconds());
        return jdbc;
    }

    /** 只读池 url：显式配了就用；否则共用兼容字段 {@code url}。 */
    private static String readOnlyUrl(Nl2SqlProperties.Datasource d) {
        if (d.getReadonlyUrl() != null && !d.getReadonlyUrl().isBlank()) {
            return d.getReadonlyUrl();
        }
        return d.getUrl();
    }

    @Bean
    public SchemaProvider nl2sqlSchemaProvider(DataSource nl2sqlAdminDataSource, Nl2SqlProperties props) {
        return new SchemaProvider(nl2sqlAdminDataSource, props);
    }

    @Bean
    public NlToSqlService nlToSqlService(ChatModel chatModel,
                                         JdbcTemplate nl2sqlReadOnlyJdbc,
                                         SchemaProvider nl2sqlSchemaProvider,
                                         Nl2SqlProperties props,
                                         AuditLogger audit) {
        SqlGuard guard = new SqlGuard(props.getAllowTables(), props.getTenantScopedTables(),
                props.getMaxRows(), props.isEnforceTenantPredicate());
        SqlQueryTool tool = new SqlQueryTool(nl2sqlReadOnlyJdbc, guard, props.getMaxToolCalls());
        SqlAssistant assistant = AiServices.builder(SqlAssistant.class)
                .chatModel(chatModel)
                .tools(tool)
                .build();
        return new NlToSqlService(assistant, nl2sqlSchemaProvider, audit, props.isNumberGrounding());
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.nl2sql.external-planner.enabled",
            havingValue = "true")
    public GuardedSqlExecutor guardedSqlExecutor(
            JdbcTemplate nl2sqlReadOnlyJdbc,
            Nl2SqlProperties props
    ) {
        SqlGuard guard = new SqlGuard(
                props.getAllowTables(),
                props.getTenantScopedTables(),
                props.getMaxRows(),
                props.isEnforceTenantPredicate());
        return new GuardedSqlExecutor(nl2sqlReadOnlyJdbc, guard);
    }

    private static HikariDataSource pool(String url, String user, String pass, boolean readOnly, String name) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(url);
        c.setUsername(user);
        c.setPassword(pass);
        c.setReadOnly(readOnly);          // 只读池再加一层 connection.setReadOnly（L1 之上的纵深）
        c.setMaximumPoolSize(readOnly ? 4 : 2);
        c.setPoolName(name);
        return new HikariDataSource(c);
    }
}
