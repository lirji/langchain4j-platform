package com.lrj.platform.analytics.controller;

import com.lrj.platform.analytics.GuardedSqlExecutor;
import com.lrj.platform.protocol.analytics.AnalyticsSqlPlanReply;
import com.lrj.platform.protocol.analytics.AnalyticsSqlPlanRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * AgentScope/Python planner 的窄执行端点。它不接受 tenantId、凭据、连接参数或绕过 guard 的选项。
 */
@RestController
@ConditionalOnProperty(name = "app.nl2sql.external-planner.enabled", havingValue = "true")
public class AnalyticsPlanController {

    private final GuardedSqlExecutor executor;

    public AnalyticsPlanController(GuardedSqlExecutor executor) {
        this.executor = executor;
    }

    @PostMapping("/analytics/sql/plans/execute")
    public AnalyticsSqlPlanReply execute(
            @RequestBody(required = false) AnalyticsSqlPlanRequest request
    ) {
        if (request == null || request.question() == null
                || request.question().isBlank() || request.sql() == null
                || request.sql().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "question and sql are required");
        }
        return executor.execute(request.question().trim(), request.sql());
    }
}
