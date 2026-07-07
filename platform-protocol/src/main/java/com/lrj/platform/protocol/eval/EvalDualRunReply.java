package com.lrj.platform.protocol.eval;

import java.time.Instant;

/**
 * 双跑门禁应答：包裹一次 oracle/candidate 双跑的门禁判定与元信息。
 *
 * @param runId       本次运行 id
 * @param suiteName   suite 名
 * @param mode        {@code "snapshot"}（PR 快照）或 {@code "live"}（nightly 现场 oracle）
 * @param gate        门禁判定（含 candidate/oracle 聚合、agreement、regressions）
 * @param startedAt   开始时刻
 * @param durationMs  总耗时
 * @param finishedAt  结束时刻
 */
public record EvalDualRunReply(String runId,
                               String suiteName,
                               String mode,
                               EvalGateResult gate,
                               Instant startedAt,
                               long durationMs,
                               Instant finishedAt) {
}
