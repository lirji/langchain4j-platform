package com.lrj.platform.protocol.agent;

import java.util.List;

/**
 * Voting 一次投票的产物（{@code POST /agent/vote} 响应）。
 *
 * @param question  投票的问题
 * @param votes     N 个独立回答（原文）
 * @param strategy  聚合策略：{@code majority} | {@code synthesis}
 * @param decision  最终决策：majority = 胜出票原文；synthesis = 聚合器 LLM 收口结果
 * @param agreement majority 策略下 = 胜出票数 / 总票数；synthesis 下为 {@code NaN}
 * @param confident majority 下 = {@code agreement >= minAgreement}；synthesis 下恒 true
 * @param tenantId  发起租户（多租户归因）
 */
public record VoteReply(String question,
                        List<String> votes,
                        String strategy,
                        String decision,
                        double agreement,
                        boolean confident,
                        String tenantId) {

    public VoteReply {
        votes = votes == null ? List.of() : List.copyOf(votes);
    }
}
