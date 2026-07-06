package com.lrj.platform.asynctask;

import com.lrj.platform.protocol.asynctask.AsyncTask;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AsyncTaskSseService {

    private static final int HISTORY_LIMIT = 64;

    private final AsyncTaskStore store;
    private final ConcurrentMap<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Deque<AsyncTaskSseEvent>> history = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public AsyncTaskSseService(AsyncTaskStore store) {
        this.store = store;
    }

    public Optional<SseEmitter> subscribe(String taskId) {
        return subscribe(taskId, null);
    }

    public Optional<SseEmitter> subscribe(String taskId, String lastEventId) {
        Optional<AsyncTask> task = store.get(taskId);
        if (task.isEmpty()) {
            return Optional.empty();
        }
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(ignored -> remove(taskId, emitter));
        List<AsyncTaskSseEvent> replay = eventsAfter(taskId, lastEventId);
        if (replay.isEmpty()) {
            send(emitter, latestEvent(taskId).orElseGet(() -> snapshotEvent(task.get())));
        } else {
            replay.forEach(event -> send(emitter, event));
        }
        if (task.get().status().isTerminal()) {
            emitter.complete();
            remove(taskId, emitter);
        }
        return Optional.of(emitter);
    }

    @EventListener
    public void onTaskEvent(AsyncTaskEvent event) {
        AsyncTaskSseEvent sseEvent = appendHistory(event.task());
        List<SseEmitter> current = emitters.get(event.task().taskId());
        if (current == null) {
            return;
        }
        for (SseEmitter emitter : current) {
            send(emitter, sseEvent);
        }
        if (event.task().status().isTerminal()) {
            current.forEach(SseEmitter::complete);
            emitters.remove(event.task().taskId());
        }
    }

    List<AsyncTaskSseEvent> eventsAfter(String taskId, String lastEventId) {
        Deque<AsyncTaskSseEvent> events = history.get(taskId);
        if (events == null || events.isEmpty() || lastEventId == null || lastEventId.isBlank()) {
            return List.of();
        }
        long after;
        try {
            after = Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException ex) {
            return List.of();
        }
        List<AsyncTaskSseEvent> replay = new ArrayList<>();
        synchronized (events) {
            for (AsyncTaskSseEvent event : events) {
                if (Long.parseLong(event.id()) > after) {
                    replay.add(event);
                }
            }
        }
        return replay;
    }

    private AsyncTaskSseEvent appendHistory(AsyncTask task) {
        AsyncTaskSseEvent event = new AsyncTaskSseEvent(String.valueOf(sequence.incrementAndGet()), task);
        Deque<AsyncTaskSseEvent> events = history.computeIfAbsent(task.taskId(), ignored -> new ArrayDeque<>());
        synchronized (events) {
            events.addLast(event);
            while (events.size() > HISTORY_LIMIT) {
                events.removeFirst();
            }
        }
        return event;
    }

    private Optional<AsyncTaskSseEvent> latestEvent(String taskId) {
        Deque<AsyncTaskSseEvent> events = history.get(taskId);
        if (events == null || events.isEmpty()) {
            return Optional.empty();
        }
        synchronized (events) {
            return Optional.ofNullable(events.peekLast());
        }
    }

    private AsyncTaskSseEvent snapshotEvent(AsyncTask task) {
        return new AsyncTaskSseEvent(String.valueOf(sequence.incrementAndGet()), task);
    }

    private void send(SseEmitter emitter, AsyncTaskSseEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(event.id())
                    .name(event.task().status().name())
                    .data(event.task()));
        } catch (IOException ex) {
            emitter.completeWithError(ex);
        }
    }

    private void remove(String taskId, SseEmitter emitter) {
        List<SseEmitter> current = emitters.get(taskId);
        if (current != null) {
            current.remove(emitter);
        }
    }

    record AsyncTaskSseEvent(String id, AsyncTask task) {
    }
}
