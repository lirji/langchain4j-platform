package com.lrj.platform.asynctask;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Task-scoped ordered event journal used by SSE replay.
 */
public interface AsyncTaskEventJournal {

    AsyncTaskStreamEvent append(String taskId,
                                String eventKey,
                                String event,
                                Object data,
                                String workerId,
                                Instant createdAt);

    List<AsyncTaskStreamEvent> eventsAfter(String taskId, long sequence);

    Optional<AsyncTaskStreamEvent> latest(String taskId);

    int cleanupBefore(Instant cutoff);
}
