package com.lrj.platform.protocol.eval;

/**
 * eval-service 回归测试套件的运行请求（跨服务 DTO）。
 * {@code targetBaseUrl} 指定被测服务的基地址，回归客户端据此对目标发起 {@code /eval/**} 套件评测。
 */
public record EvalSuiteRunRequest(String targetBaseUrl) {
}
