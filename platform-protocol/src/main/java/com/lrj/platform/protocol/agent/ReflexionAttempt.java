package com.lrj.platform.protocol.agent;

/**
 * Reflexion 自省环单轮尝试的评分快照。
 *
 * @param n            轮次（1 = 初答，&gt;1 = improve 后）
 * @param answer       本轮答案
 * @param aggregate    三维加权聚合分（{@code app.agent.reflexion.weights.*}）
 * @param correctness  正确性 0.0-1.0
 * @param completeness 完整性 0.0-1.0
 * @param clarity      清晰度 0.0-1.0
 * @param mainIssue    评审给出的最该改进的单点（'n/a' = 已足够好）
 */
public record ReflexionAttempt(int n,
                               String answer,
                               double aggregate,
                               double correctness,
                               double completeness,
                               double clarity,
                               String mainIssue) {
}
