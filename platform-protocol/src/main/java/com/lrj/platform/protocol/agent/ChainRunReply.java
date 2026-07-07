package com.lrj.platform.protocol.agent;

import java.util.List;

/**
 * Prompt Chaining 整条链的产物（{@code POST /agent/chain} 响应）。
 *
 * @param input       初始输入
 * @param steps       逐步 trace（含每步输出与 gate 结果）
 * @param finalOutput {@code completed=true} 时为最后一步输出；否则为被 gate 拦下那步的输出
 * @param completed   true = 全部步骤通过；false = 某步 gate 未过被短路终止
 * @param tenantId    发起租户（多租户归因）
 */
public record ChainRunReply(String input,
                            List<ChainStepResult> steps,
                            String finalOutput,
                            boolean completed,
                            String tenantId) {

    public ChainRunReply {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
