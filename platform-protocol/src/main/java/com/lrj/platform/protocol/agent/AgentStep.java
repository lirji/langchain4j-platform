package com.lrj.platform.protocol.agent;

/**
 * ReAct 推理循环中的单步 trace：第 {@code n} 步的思考 {@code thought}、选取的动作 {@code action}
 * 及其输入 {@code actionInput}、以及工具执行返回的观察 {@code observation}。见 {@link AgentRunReply}。
 */
public record AgentStep(int n, String thought, String action, String actionInput, String observation) {
}
