package com.lrj.platform.analytics.controller;

import com.lrj.platform.analytics.SchemaProvider;
import com.lrj.platform.protocol.analytics.AnalyticsTableSchemaReply;
import com.lrj.platform.protocol.analytics.AnalyticsTablesReply;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * NL2SQL 探表端点：让数据分析智能体「按需探索」库结构，而不是只能依赖 {@code SqlAssistant}
 * 里静态全量注入的 schema。仅在 {@code app.nl2sql.enabled=true} 时注册（与 {@link AnalyticsController}
 * 同门控，{@link SchemaProvider} 也才存在）。
 *
 * <p>只暴露白名单表（{@code app.nl2sql.allow-tables}，与 {@code SqlGuard} L3 同源）的结构元数据
 * （表名 / 列 / 类型 / 注释 / 枚举取值），不含租户业务行数据，安全面与 NL2SQL system prompt 注入一致。
 * 非白名单/不存在的表一律 404，不泄露其是否存在。租户隔离沿用过滤器链注入的 {@code TenantContext}。
 */
@RestController
@ConditionalOnProperty(name = "app.nl2sql.enabled", havingValue = "true")
public class AnalyticsSchemaController {

    private final SchemaProvider schema;

    public AnalyticsSchemaController(SchemaProvider nl2sqlSchemaProvider) {
        this.schema = nl2sqlSchemaProvider;
    }

    @GetMapping("/analytics/schema/tables")
    public AnalyticsTablesReply tables() {
        return new AnalyticsTablesReply(schema.tableNames());
    }

    @GetMapping("/analytics/schema/tables/{table}")
    public ResponseEntity<AnalyticsTableSchemaReply> describe(@PathVariable String table) {
        String block = schema.describe(table);
        if (block == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new AnalyticsTableSchemaReply(table, block));
    }
}
