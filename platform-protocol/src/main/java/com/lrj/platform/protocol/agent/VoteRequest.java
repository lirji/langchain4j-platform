package com.lrj.platform.protocol.agent;

/**
 * Voting 入口请求（{@code POST /agent/vote}）。
 *
 * @param question 待投票的问题
 * @param n        并行投票次数；{@code null} 时用服务端默认 {@code app.agent.voting.n}
 */
public record VoteRequest(String question, Integer n) {
}
