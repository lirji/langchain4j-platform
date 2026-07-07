package com.lrj.platform.protocol.agent;

import java.util.List;

/**
 * Reflexion 自省环产物（{@code POST /agent/reflexive} 响应 / SSE {@code done} 事件负载）。
 *
 * @param question            原问题
 * @param finalAnswer         最终答案（最后一轮）
 * @param attempts            各轮尝试的评分轨迹（含初答与每次 improve）
 * @param acceptedByThreshold 是否因聚合分达阈值而收敛（false = 用尽最大轮次仍未达阈值）
 * @param tenantId            发起租户（多租户归因）
 */
public record ReflexionReply(String question,
                             String finalAnswer,
                             List<ReflexionAttempt> attempts,
                             boolean acceptedByThreshold,
                             String tenantId) {

    public ReflexionReply {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }
}
