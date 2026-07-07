package com.lrj.platform.protocol.eval;

/**
 * 双跑门禁请求：同一 suite（经 EvalSuiteLoader 单一来源）分别打 oracle 与 candidate。
 *
 * <p>两种形态由 {@code oracleSnapshot} 是否存在推断：
 * <ul>
 *   <li><b>PR 快照模式</b>：给 {@code oracleSnapshot}（预存 oracle 响应/分数的快照名），只起 candidate，快/稳。</li>
 *   <li><b>nightly live 模式</b>：给 {@code oracleBaseUrl}（现场起的冻结单体），oracle/candidate 都实打。</li>
 * </ul>
 * 容差字段为空时回退到 {@code app.eval.gate.*} 配置默认值。
 *
 * @param suiteName             suite 名（必填，两个目标共用同一 suite）
 * @param candidateBaseUrl      candidate 目标地址（空则用 default-target-base-url）
 * @param oracleBaseUrl         oracle 目标地址（live 模式；与 oracleSnapshot 二选一）
 * @param oracleSnapshot        冻结 oracle 快照名（PR 模式；优先于 oracleBaseUrl）
 * @param runs                  每 case 重复次数（空则用配置默认）
 * @param passRateTolerance     覆盖默认 passRate 容差（可空）
 * @param averageScoreTolerance 覆盖默认 averageScore 容差（可空）
 * @param minAgreement          覆盖默认 agreement 阈值（可空）
 */
public record EvalDualRunRequest(String suiteName,
                                 String candidateBaseUrl,
                                 String oracleBaseUrl,
                                 String oracleSnapshot,
                                 Integer runs,
                                 Double passRateTolerance,
                                 Double averageScoreTolerance,
                                 Double minAgreement) {
}
