package com.lrj.platform.protocol.asynctask;

/**
 * worker 回报异步任务状态的请求（更新任务状态时提交）：新状态 {@link AsyncTaskStatus}、
 * 结果或错误，及可选 {@code workerId}（用于校验租约持有者）。
 */
public record AsyncTaskStatusUpdateRequest(AsyncTaskStatus status,
                                           Object result,
                                           String error,
                                           String workerId) {

    public AsyncTaskStatusUpdateRequest(AsyncTaskStatus status, Object result, String error) {
        this(status, result, error, null);
    }
}
