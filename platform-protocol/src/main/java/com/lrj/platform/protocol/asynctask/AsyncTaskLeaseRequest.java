package com.lrj.platform.protocol.asynctask;

/**
 * worker 抢占（租约）一个异步任务的请求：{@code workerId} 标识领取者，{@code leaseSeconds}
 * 指定租约时长（到期未续则任务可被其它 worker 重新领取）。
 */
public record AsyncTaskLeaseRequest(String workerId,
                                    Long leaseSeconds) {
}
