package com.lrj.platform.protocol.agent;

/**
 * 判官（critic）对一次多 Agent DAG 综合结果的评分：正确性 / 完整性 / 清晰度三维打分，
 * 外加 {@code mainIssue} 主要问题描述。用于判定是否达阈值接受或触发重规划，见 {@link AgentDagAttempt}。
 */
public record AgentDagCritique(double correctness,
                               double completeness,
                               double clarity,
                               String mainIssue) {
}
