package com.lrj.platform.asynctask;

import com.lrj.platform.protocol.asynctask.AsyncTask;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * 异步任务的 SSE 流式推送服务。监听 {@link AsyncTaskEvent}，把每次任务状态变更广播给该任务的所有
 * {@link SseEmitter} 订阅者，并维护一段有界历史（{@code HISTORY_LIMIT}）以支持按 Last-Event-ID
 * 断点续传（{@link #eventsAfter}）；任务进入终态时自动 complete 并清理订阅。由 {@link AsyncTaskController}
 * 的 {@code /async/tasks/{id}/stream} 调用。
 */
@Service
public class AsyncTaskSseService {

    private static final int HISTORY_LIMIT = 64;

    private final AsyncTaskStore store;
    private final AsyncTaskEventJournal journal;
    private final ConcurrentMap<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Deque<AsyncTaskSseEvent>> history = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> taskLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<SseEmitter, Long> watermarks = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public AsyncTaskSseService(AsyncTaskStore store) {
        this(store, (AsyncTaskEventJournal) null);
    }

    @Autowired
    public AsyncTaskSseService(AsyncTaskStore store,
                               ObjectProvider<AsyncTaskEventJournal> journal) {
        this(store, journal == null ? null : journal.getIfAvailable());
    }

    AsyncTaskSseService(AsyncTaskStore store, AsyncTaskEventJournal journal) {
        this.store = store;
        this.journal = journal;
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
        Object taskLock = taskLocks.computeIfAbsent(taskId, ignored -> new Object());
        synchronized (taskLock) {
            emitters.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
            watermarks.put(
                    emitter,
                    lastEventId == null || lastEventId.isBlank()
                            ? -1L
                            : parseSequence(lastEventId));
            emitter.onCompletion(() -> remove(taskId, emitter));
            emitter.onTimeout(() -> remove(taskId, emitter));
            emitter.onError(ignored -> remove(taskId, emitter));
            List<AsyncTaskSseEvent> replay = eventsAfter(taskId, lastEventId);
            if (journal != null) {
                long after = parseSequence(lastEventId);
                List<AsyncTaskStreamEvent> persisted = journal.eventsAfter(taskId, after);
                if (persisted.isEmpty()) {
                    journal.latest(taskId).ifPresentOrElse(
                            event -> send(emitter, event),
                            () -> send(emitter, snapshotStreamEvent(task.get())));
                } else {
                    persisted.forEach(event -> send(emitter, event));
                }
            } else if (replay.isEmpty()) {
                send(emitter, latestEvent(taskId).orElseGet(() -> snapshotEvent(task.get())));
            } else {
                replay.forEach(event -> send(emitter, event));
            }
            if (task.get().status().isTerminal()) {
                emitter.complete();
                remove(taskId, emitter);
            }
        }
        return Optional.of(emitter);
    }

    @EventListener
    public void onTaskEvent(AsyncTaskEvent event) {
        String taskId = event.task().taskId();
        Object taskLock = taskLocks.computeIfAbsent(taskId, ignored -> new Object());
        synchronized (taskLock) {
            if (event.streamEvent() != null) {
                List<SseEmitter> persisted = emitters.get(taskId);
                if (persisted != null) {
                    for (SseEmitter emitter : persisted) {
                        send(emitter, event.streamEvent());
                    }
                }
                completeTerminal(event.task());
                return;
            }
            AsyncTaskSseEvent sseEvent = appendHistory(event.task());
            List<SseEmitter> current = emitters.get(taskId);
            if (current == null) {
                return;
            }
            for (SseEmitter emitter : current) {
                send(emitter, sseEvent);
            }
            completeTerminal(event.task());
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
        long sequence = Long.parseLong(event.id());
        if (sequence <= watermarks.getOrDefault(emitter, 0L)) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .id(event.id())
                    .name(event.task().status().name())
                    .data(event.task()));
            watermarks.put(emitter, sequence);
        } catch (IOException ex) {
            emitter.completeWithError(ex);
        }
    }

    private void send(SseEmitter emitter, AsyncTaskStreamEvent event) {
        if (event.sequence() <= watermarks.getOrDefault(emitter, 0L)) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.sequence()))
                    .name(event.event())
                    .data(event.data()));
            watermarks.put(emitter, event.sequence());
        } catch (IOException ex) {
            emitter.completeWithError(ex);
        }
    }

    private AsyncTaskStreamEvent snapshotStreamEvent(AsyncTask task) {
        return new AsyncTaskStreamEvent(
                task.taskId(),
                0,
                "snapshot",
                task.status().name(),
                task,
                task.updatedAt(),
                null);
    }

    private static long parseSequence(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(lastEventId.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void completeTerminal(AsyncTask task) {
        if (!task.status().isTerminal()) {
            return;
        }
        List<SseEmitter> current = emitters.get(task.taskId());
        if (current != null) {
            current.forEach(SseEmitter::complete);
            emitters.remove(task.taskId());
        }
    }

    private void remove(String taskId, SseEmitter emitter) {
        watermarks.remove(emitter);
        List<SseEmitter> current = emitters.get(taskId);
        if (current != null) {
            current.remove(emitter);
            if (current.isEmpty() && emitters.remove(taskId, current)) {
                taskLocks.remove(taskId);
            }
        }
    }

    record AsyncTaskSseEvent(String id, AsyncTask task) {
    }
}
