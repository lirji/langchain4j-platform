package com.lrj.platform.asynctask;

/**
 * Worker request for appending one idempotent progress event.
 */
public record AsyncTaskEventAppendRequest(String eventKey,
                                          String event,
                                          Object data,
                                          String workerId,
                                          Long leaseEpoch) {

    public AsyncTaskEventAppendRequest(
            String eventKey,
            String event,
            Object data,
            String workerId) {
        this(eventKey, event, data, workerId, null);
    }
}
