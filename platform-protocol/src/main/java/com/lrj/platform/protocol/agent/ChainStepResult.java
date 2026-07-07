package com.lrj.platform.protocol.agent;

/**
 * Prompt Chaining 单步产物：某一步的输出 + 确定性 gate 判定结果。
 *
 * @param name       步骤名（trace / 日志用）
 * @param output     该步 {@code ChainLink} 的输出
 * @param gatePassed 步间确定性 gate 是否通过
 * @param gateReason gate 未通过时的原因；通过时为 {@code null}
 */
public record ChainStepResult(String name, String output, boolean gatePassed, String gateReason) {
}
