package com.lrj.platform.asynctask;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Development/test event journal. Production async traffic must use the JDBC implementation.
 */
@Component
@ConditionalOnProperty(name = "app.async-task.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryAsyncTaskEventJournal implements AsyncTaskEventJournal {

    private final ConcurrentMap<String, List<AsyncTaskStreamEvent>> events = new ConcurrentHashMap<>();

    @Override
    public AsyncTaskStreamEvent append(String taskId,
                                       String eventKey,
                                       String event,
                                       Object data,
                                       String workerId,
                                       Instant createdAt) {
        List<AsyncTaskStreamEvent> taskEvents = events.computeIfAbsent(taskId, ignored -> new ArrayList<>());
        synchronized (taskEvents) {
            Optional<AsyncTaskStreamEvent> duplicate = taskEvents.stream()
                    .filter(item -> item.eventKey().equals(eventKey))
                    .findFirst();
            if (duplicate.isPresent()) {
                return duplicate.get();
            }
            long sequence = taskEvents.isEmpty() ? 1 : taskEvents.getLast().sequence() + 1;
            AsyncTaskStreamEvent appended = new AsyncTaskStreamEvent(
                    taskId, sequence, eventKey, event, data, createdAt, workerId);
            taskEvents.add(appended);
            return appended;
        }
    }

    @Override
    public List<AsyncTaskStreamEvent> eventsAfter(String taskId, long sequence) {
        List<AsyncTaskStreamEvent> taskEvents = events.get(taskId);
        if (taskEvents == null) {
            return List.of();
        }
        synchronized (taskEvents) {
            return taskEvents.stream()
                    .filter(item -> item.sequence() > sequence)
                    .sorted(Comparator.comparingLong(AsyncTaskStreamEvent::sequence))
                    .toList();
        }
    }

    @Override
    public Optional<AsyncTaskStreamEvent> latest(String taskId) {
        List<AsyncTaskStreamEvent> taskEvents = events.get(taskId);
        if (taskEvents == null) {
            return Optional.empty();
        }
        synchronized (taskEvents) {
            return taskEvents.isEmpty() ? Optional.empty() : Optional.of(taskEvents.getLast());
        }
    }

    @Override
    public int cleanupBefore(Instant cutoff) {
        int removed = 0;
        for (Map.Entry<String, List<AsyncTaskStreamEvent>> entry : events.entrySet()) {
            List<AsyncTaskStreamEvent> taskEvents = entry.getValue();
            synchronized (taskEvents) {
                int before = taskEvents.size();
                taskEvents.removeIf(item -> item.createdAt().isBefore(cutoff));
                removed += before - taskEvents.size();
                if (taskEvents.isEmpty()) {
                    events.remove(entry.getKey(), taskEvents);
                }
            }
        }
        return removed;
    }
}
