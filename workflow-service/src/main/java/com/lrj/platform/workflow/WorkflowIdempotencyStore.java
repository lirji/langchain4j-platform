package com.lrj.platform.workflow;

/**
 * 退款流程副作用的权威幂等账本。
 *
 * <p>实现必须用数据库唯一约束串行化同一 {@code tenant + operation + keyHash} 的竞争者，且
 * {@link #claim}、Flowable 流程创建与 {@link #attachInstance} 必须参加同一个
 * {@code workflowTransactionManager} 事务。这样创建失败会连同 claim 一起回滚，不会留下永久占位；
 * 并发重放只能观察到首个事务已经绑定的同一流程实例。
 */
public interface WorkflowIdempotencyStore {

    /**
     * 竞争一个幂等键。
     *
     * @return 首次请求返回 {@link Claim#acquired()} 为 true；已成功提交的同请求返回其流程实例 id
     * @throws IdempotencyConflictException 同一键已绑定到不同规范化请求，或账本状态不完整
     */
    Claim claim(String tenantId,
                String operation,
                String keyHash,
                String requestHash,
                String businessKey);

    /** 把首次 claim 与刚创建（或升级前已存在）的 Flowable 实例在当前事务内绑定。 */
    void attachInstance(String tenantId,
                        String operation,
                        String keyHash,
                        String requestHash,
                        String instanceId);

    /** 数据清除/保留期清理时移除实例对应的幂等绑定，使账本不会指向已删除流程。 */
    void deleteByInstance(String instanceId);

    record Claim(boolean acquired, String instanceId) {
        static Claim acquiredClaim() {
            return new Claim(true, null);
        }

        static Claim replay(String instanceId) {
            return new Claim(false, instanceId);
        }
    }

    final class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) {
            super(message);
        }
    }
}
