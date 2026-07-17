package com.lrj.platform.agent.async;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把本地 agent 异步任务事件镜像到外部 async-task-service。监听 {@link AgentTaskEvent}，经
 * {@link ExternalAsyncTaskClient} 首次 create、后续 update（PENDING 之外的状态）。仅在外部服务作为
 * 「只镜像」从库（非 authoritative）时生效；由 {@code app.agent.async.external.enabled} 门控（默认关）。
 */
@Component
@ConditionalOnProperty(name = "app.agent.async.external.enabled", havingValue = "true")
public class AgentAsyncTaskMirror {

    private final ExternalAsyncTaskClient client;
    private final ExternalAsyncTaskProperties properties;
    private final Set<String> created = ConcurrentHashMap.newKeySet();

    public AgentAsyncTaskMirror(ExternalAsyncTaskClient client, ExternalAsyncTaskProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @EventListener
    public void onTaskEvent(AgentTaskEvent event) {
        AgentAsyncTask task = event.task();
        if (properties.isAuthoritative()) {
            return;
        }
        if (created.add(task.taskId()) && !client.create(task)) {
            created.remove(task.taskId());
            return;
        }
        if (task.status() != AgentTaskStatus.PENDING) {
            client.update(task);
        }
    }

}
