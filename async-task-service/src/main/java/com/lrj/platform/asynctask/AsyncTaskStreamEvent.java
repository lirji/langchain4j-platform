package com.lrj.platform.asynctask;

import java.time.Instant;

/**
 * Language-neutral persisted SSE event. Sequence numbers are scoped to one task.
 */
public record AsyncTaskStreamEvent(String taskId,
                                   long sequence,
                                   String eventKey,
                                   String event,
                                   Object data,
                                   Instant createdAt,
                                   String workerId) {
}
