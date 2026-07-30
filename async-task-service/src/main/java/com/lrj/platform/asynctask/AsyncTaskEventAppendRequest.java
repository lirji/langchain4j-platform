package com.lrj.platform.asynctask;

/**
 * Worker request for appending one idempotent progress event.
 */
public record AsyncTaskEventAppendRequest(String eventKey,
                                          String event,
                                          Object data,
                                          String workerId) {
}
