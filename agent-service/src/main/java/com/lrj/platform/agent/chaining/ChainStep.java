package com.lrj.platform.agent.chaining;

/**
 * Prompt Chaining 的一步定义：一条 {@code instruction} + 可选的确定性 <b>gate</b>（步间程序化校验）。
 *
 * <p>gate 是 Anthropic Prompt Chaining 模式的关键——在步与步之间插一道<strong>非 LLM 的确定性检查</strong>，
 * 不达标就<strong>短路终止</strong>整条链（避免把跑偏的中间结果继续喂下去、烧后续 token）。三种 gate 可叠加，
 * 全部为空 = 该步无 gate。
 *
 * <p>不可变 config record，由 {@code app.agent.chaining.steps} yml 经构造器绑定（缺省字段取 record 默认：
 * {@code null} / 0）。
 *
 * @param name            步骤名（trace / 日志用）
 * @param instruction     喂给 {@link ChainLink} 的指令
 * @param gateMinLength   gate：输出最小长度（字符）。&le;0 = 关
 * @param gateMustContain gate：输出必须包含的子串。空/null = 关
 * @param gateMustMatch   gate：输出必须命中的正则（find 语义）。空/null = 关
 */
public record ChainStep(String name,
                        String instruction,
                        int gateMinLength,
                        String gateMustContain,
                        String gateMustMatch) {
}
