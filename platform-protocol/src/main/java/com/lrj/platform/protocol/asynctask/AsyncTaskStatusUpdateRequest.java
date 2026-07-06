package com.lrj.platform.protocol.asynctask;

public record AsyncTaskStatusUpdateRequest(AsyncTaskStatus status,
                                           Object result,
                                           String error,
                                           String workerId) {

    public AsyncTaskStatusUpdateRequest(AsyncTaskStatus status, Object result, String error) {
        this(status, result, error, null);
    }
}
