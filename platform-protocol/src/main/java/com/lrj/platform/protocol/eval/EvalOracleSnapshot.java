package com.lrj.platform.protocol.eval;

/**
 * 冻结 oracle 快照：预存单体（oracle）在某 suite 上的聚合响应/分数，供 PR 门禁在不起单体的情况下比对。
 *
 * <p>由 nightly live 双跑或人工从冻结单体一次性抓取后提交进仓库
 * （{@code classpath:eval/snapshots/<name>.json} 或 {@code app.eval.snapshot-directory}）。
 */
public record EvalOracleSnapshot(String suiteName,
                                 EvalTargetSummary oracle) {
}
