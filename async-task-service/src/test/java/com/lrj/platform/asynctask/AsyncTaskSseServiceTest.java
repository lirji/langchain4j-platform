package com.lrj.platform.asynctask;

import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AsyncTaskSseServiceTest：验证 {@link AsyncTaskSseService} 的历史重放语义——按 Last-Event-ID 只重放其后的事件、
 * 忽略非法 lastEventId，以及有界历史窗口（保留最近 {@code HISTORY_LIMIT} 条）下的裁剪行为。
 */
class AsyncTaskSseServiceTest {

    @Test
    void replaysEventsAfterLastEventId() {
        AsyncTaskStore store = new AsyncTaskStore(Duration.ofHours(1));
        AsyncTask task = task("task-1", AsyncTaskStatus.PENDING);
        store.put(task);
        AsyncTaskSseService sse = new AsyncTaskSseService(store);

        sse.onTaskEvent(new AsyncTaskEvent(task));
        sse.onTaskEvent(new AsyncTaskEvent(task("task-1", AsyncTaskStatus.RUNNING)));
        sse.onTaskEvent(new AsyncTaskEvent(task("task-1", AsyncTaskStatus.SUCCEEDED)));

        var replay = sse.eventsAfter("task-1", "1");

        assertThat(replay).hasSize(2);
        assertThat(replay).extracting(event -> event.task().status())
                .containsExactly(AsyncTaskStatus.RUNNING, AsyncTaskStatus.SUCCEEDED);
        assertThat(replay).extracting(AsyncTaskSseService.AsyncTaskSseEvent::id)
                .containsExactly("2", "3");
    }

    @Test
    void ignoresInvalidLastEventId() {
        AsyncTaskStore store = new AsyncTaskStore(Duration.ofHours(1));
        AsyncTask task = task("task-1", AsyncTaskStatus.PENDING);
        store.put(task);
        AsyncTaskSseService sse = new AsyncTaskSseService(store);
        sse.onTaskEvent(new AsyncTaskEvent(task));

        assertThat(sse.eventsAfter("task-1", "bad")).isEmpty();
    }

    @Test
    void keepsOnlyRecentHistoryWindow() {
        AsyncTaskStore store = new AsyncTaskStore(Duration.ofHours(1));
        AsyncTask task = task("task-1", AsyncTaskStatus.PENDING);
        store.put(task);
        AsyncTaskSseService sse = new AsyncTaskSseService(store);

        for (int i = 0; i < 70; i++) {
            sse.onTaskEvent(new AsyncTaskEvent(task("task-1", AsyncTaskStatus.RUNNING)));
        }

        var replay = sse.eventsAfter("task-1", "0");

        assertThat(replay).hasSize(64);
        assertThat(replay.getFirst().id()).isEqualTo("7");
        assertThat(replay.getLast().id()).isEqualTo("70");
    }

    private static AsyncTask task(String taskId, AsyncTaskStatus status) {
        Instant now = Instant.now();
        return new AsyncTask(
                taskId,
                "acme",
                "alice",
                "agent.run",
                status,
                Map.of(),
                null,
                null,
                null,
                now,
                now,
                status.isTerminal() ? now : null);
    }
}
