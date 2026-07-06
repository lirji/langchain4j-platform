package com.lrj.platform.analytics.controller;

import com.lrj.platform.analytics.NlToSqlService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * NL2SQL / ChatBI 端点。仅在 {@code app.nl2sql.enabled=true} 时注册（{@link NlToSqlService} 也才存在）。
 *
 * <p>{@code POST /chat/sql} body {@code {"question":"上月退款 top5 客户"}} →
 * 返回 {@code {question, sql, rowCount, rows, answer, guardBlocked}}。
 * <strong>sql 一并回传是刻意的</strong>：可审计 + 前端可"查看/复跑" + 一眼区分是生成错还是解读错。
 * 租户隔离沿用现有过滤器链注入的 {@code TenantContext}（controller 不感知，SQL 层按 tenant_id 过滤）。
 */
@RestController
@ConditionalOnProperty(name = "app.nl2sql.enabled", havingValue = "true")
public class AnalyticsController {

    private final NlToSqlService nlToSqlService;

    public AnalyticsController(NlToSqlService nlToSqlService) {
        this.nlToSqlService = nlToSqlService;
    }

    @PostMapping({"/chat/sql", "/analytics/sql"})
    public NlToSqlService.Result chatSql(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "");
        return nlToSqlService.ask(question);
    }
}
