package com.lrj.platform.protocol.analytics;

/**
 * agent→analytics 的 NL2SQL 请求契约（最小）。question 为自然语言业务问题；
 * 租户身份不进 body，随内部 JWT 跨网络跳传播。
 */
public record AnalyticsSqlRequest(String question) {
}
