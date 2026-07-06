package com.lrj.platform.agent.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;

@Component
public class AgentTaskStore {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskStore.class);

    private final ConcurrentMap<String, AgentAsyncTask> tasks = new ConcurrentHashMap<>();
    private final Duration ttl;

    public AgentTaskStore(@Value("${app.agent.async.task-ttl:PT24H}") Duration ttl) {
        this.ttl = ttl;
    }

    public void put(AgentAsyncTask task) {
        tasks.put(task.taskId(), task);
    }

    public Optional<AgentAsyncTask> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public Optional<AgentAsyncTask> update(String taskId, UnaryOperator<AgentAsyncTask> updater) {
        return Optional.ofNullable(tasks.computeIfPresent(taskId, (key, task) -> updater.apply(task)));
    }

    public List<AgentAsyncTask> listByTenant(String tenantId) {
        return tasks.values().stream()
                .filter(task -> tenantId.equals(task.tenantId()))
                .sorted((left, right) -> right.createdAt().compareTo(left.createdAt()))
                .toList();
    }

    @Scheduled(fixedDelayString = "${app.agent.async.cleanup-delay-ms:60000}",
            initialDelayString = "${app.agent.async.cleanup-initial-delay-ms:60000}")
    public void cleanup() {
        Instant cutoff = Instant.now().minus(ttl);
        int removed = 0;
        for (Map.Entry<String, AgentAsyncTask> entry : tasks.entrySet()) {
            AgentAsyncTask task = entry.getValue();
            if (task.finishedAt() != null && task.finishedAt().isBefore(cutoff)
                    && tasks.remove(entry.getKey(), task)) {
                removed++;
            }
        }
        if (removed > 0) {
            log.info("agent task cleanup removed {} expired tasks ttl={}", removed, ttl);
        }
    }
}
