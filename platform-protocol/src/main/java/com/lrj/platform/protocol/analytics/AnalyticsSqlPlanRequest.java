package com.lrj.platform.protocol.analytics;

/**
 * 外部 AI planner 向 Java analytics executor 提交的候选 SQL。
 * tenantId 禁止进入 body，只能来自已验证的内部请求上下文。
 */
public record AnalyticsSqlPlanRequest(String question, String sql) {
}
