package com.lrj.platform.metering.budget;

/** Java 与 Python 共用的租户日预算权威，用户/操作绑定阻止其他请求结算别人的预留。 */
public interface BudgetLedger {
    /** 同一操作同额度重试返回同一预留；异参数或已结算操作不能再次调用模型。 */
    Reservation reserve(String tenant, String user, String operation, long tokens);
    /** usage 完整才结算，重复相同结算不重复记账；未知 usage 保持预留。 */
    void settle(Reservation reservation, long actualTokens);

    /** 日归属以准入时为准；跨午夜调用继续结算准入日，不能释放次日别人的额度。 */
    record Reservation(String tenantId, String userId, String operationId, String day, long reservedTokens) {}

    /** 额度或容量不足属于明确拒绝，不调用模型。 */
    final class Exceeded extends RuntimeException {
        public Exceeded() { super("tenant token budget exceeded"); }
    }
    /** 账本不可用必须停止新模型调用，业务端转换为可重试的 503。 */
    final class Unavailable extends RuntimeException {
        public Unavailable(Throwable cause) { super("tenant token budget unavailable", cause); }
    }
    /** 操作键冲突或重复执行必须拒绝，不能把幂等 RPC 当作模型执行授权重放。 */
    final class Conflict extends RuntimeException {
        public Conflict() { super("budget operation conflicts with its prior state"); }
    }
}
