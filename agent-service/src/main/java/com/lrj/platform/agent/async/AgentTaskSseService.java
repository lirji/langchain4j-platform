package com.lrj.platform.agent.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent 异步任务的服务端推送（SSE）中心。按 {@code taskId} 维护 {@link SseEmitter} 订阅表，
 * {@link #subscribe(String)} 订阅时先补发当前任务快照、任务已终态则立即关闭连接；同时监听
 * {@link AgentTaskEvent}（状态变更）与 {@link AgentTaskProgressEvent}（中间进度）并转发给对应订阅方。
 * 默认随 {@code app.agent.enabled} 装配。
 */
@Service
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class AgentTaskSseService {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskSseService.class);
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final AgentAsyncTaskService taskService;
    private final ConcurrentMap<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public AgentTaskSseService(AgentAsyncTaskService taskService) {
        this.taskService = taskService;
    }

    public Optional<SseEmitter> subscribe(String taskId) {
        Optional<AgentAsyncTask> task = taskService.get(taskId);
        if (task.isEmpty()) {
            return Optional.empty();
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(ignored -> remove(taskId, emitter));
        send(emitter, task.get());
        if (task.get().status().isTerminal()) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
        return Optional.of(emitter);
    }

    @EventListener
    public void onTaskEvent(AgentTaskEvent event) {
        AgentAsyncTask task = event.task();
        List<SseEmitter> list = emitters.get(task.taskId());
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            send(emitter, task);
            if (task.status().isTerminal()) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @EventListener
    public void onProgressEvent(AgentTaskProgressEvent event) {
        AgentTaskProgress progress = event.progress();
        List<SseEmitter> list = emitters.get(progress.taskId());
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            sendProgress(emitter, progress);
        }
    }

    private void send(SseEmitter emitter, AgentAsyncTask task) {
        try {
            emitter.send(SseEmitter.event()
                    .name(task.status().name())
                    .data(task));
        } catch (IOException ex) {
            log.debug("agent task sse send failed task={}: {}", task.taskId(), ex.toString());
        }
    }

    private void sendProgress(SseEmitter emitter, AgentTaskProgress progress) {
        try {
            emitter.send(SseEmitter.event()
                    .name(progress.event())
                    .data(progress));
        } catch (IOException ex) {
            log.debug("agent task progress sse send failed task={} event={}: {}",
                    progress.taskId(), progress.event(), ex.toString());
        }
    }

    private void remove(String taskId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(taskId, list);
            }
        }
    }
}
