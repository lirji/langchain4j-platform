package com.lrj.platform.protocol.asynctask;

/**
 * 异步任务的生命周期状态枚举：PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED。
 * {@link #isTerminal()} 判定是否已达终态（成功 / 失败 / 取消）。见 {@link AsyncTask}。
 */
public enum AsyncTaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
