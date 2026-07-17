package com.lrj.platform.agent.async;

import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskCreateRequest;
import com.lrj.platform.protocol.asynctask.AsyncTaskLeaseRequest;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatusUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 将 Agent 异步任务镜像/托管到中央 async-task-service（:8086）的 REST 客户端。封装对
 * {@code /async/tasks/**} 的创建、查询、列表、状态更新、租约续期与取消调用，在本地任务存储与
 * 平台统一任务中心之间同步；请求经 {@code asyncTaskRestTemplate} 透传租户与 traceId。
 * 仅当 {@code app.agent.async.external.enabled=true} 时装配，配置见 {@link ExternalAsyncTaskProperties}。
 */
@Component
@ConditionalOnProperty(name = "app.agent.async.external.enabled", havingValue = "true")
public class ExternalAsyncTaskClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalAsyncTaskClient.class);

    private final RestTemplate restTemplate;
    private final ExternalAsyncTaskProperties properties;

    public ExternalAsyncTaskClient(@Qualifier("asyncTaskRestTemplate") RestTemplate restTemplate,
                                   ExternalAsyncTaskProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public boolean create(AgentAsyncTask task) {
        try {
            return restTemplate.postForEntity("/async/tasks", createRequest(task), AsyncTask.class).getStatusCode().is2xxSuccessful();
        } catch (RestClientException ex) {
            log.warn("mirror agent task create failed taskId={}: {}", task.taskId(), ex.toString());
            return false;
        }
    }

    public Optional<AgentAsyncTask> get(String taskId) {
        try {
            AsyncTask task = restTemplate.getForObject("/async/tasks/{taskId}", AsyncTask.class, taskId);
            return Optional.ofNullable(task).map(ExternalAsyncTaskClient::toAgentTask);
        } catch (RestClientException ex) {
            log.warn("agent async task get failed taskId={}: {}", taskId, ex.toString());
            return Optional.empty();
        }
    }

    public List<AgentAsyncTask> listMine() {
        try {
            ResponseEntity<List<AsyncTask>> response = restTemplate.exchange(
                    "/async/tasks",
                    HttpMethod.GET,
                    HttpEntity.EMPTY,
                    new ParameterizedTypeReference<>() {});
            List<AsyncTask> body = response.getBody();
            return body == null ? List.of() : body.stream().map(ExternalAsyncTaskClient::toAgentTask).toList();
        } catch (RestClientException ex) {
            log.warn("agent async task list failed: {}", ex.toString());
            return List.of();
        }
    }

    public boolean update(AgentAsyncTask task) {
        try {
            ResponseEntity<Void> response = restTemplate.exchange(
                    "/async/tasks/{taskId}/status",
                    HttpMethod.PATCH,
                    new HttpEntity<>(new AsyncTaskStatusUpdateRequest(
                            status(task.status()),
                            task.result(),
                            task.error(),
                            workerId())),
                    Void.class,
                    task.taskId());
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException ex) {
            log.warn("mirror agent task update failed taskId={} status={}: {}",
                    task.taskId(), task.status(), ex.toString());
            return false;
        }
    }

    public boolean lease(String taskId) {
        try {
            ResponseEntity<AsyncTask> response = restTemplate.postForEntity(
                    "/async/tasks/{taskId}/lease",
                    new AsyncTaskLeaseRequest(workerId(), properties.getLeaseSeconds()),
                    AsyncTask.class,
                    taskId);
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException ex) {
            log.warn("agent async task lease failed taskId={} workerId={}: {}",
                    taskId, workerId(), ex.toString());
            return false;
        }
    }

    public boolean cancel(String taskId) {
        try {
            ResponseEntity<Void> response = restTemplate.exchange(
                    "/async/tasks/{taskId}", HttpMethod.DELETE, HttpEntity.EMPTY, Void.class, taskId);
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException ex) {
            log.warn("agent async task cancel failed taskId={}: {}", taskId, ex.toString());
            return false;
        }
    }

    private AsyncTaskCreateRequest createRequest(AgentAsyncTask task) {
        Map<String, Object> input = task.input();
        String webhookUrl = null;
        if (!properties.isMirrorWebhook()) {
            input = withoutWebhook(input);
        } else {
            Object raw = input.get("webhookUrl");
            webhookUrl = raw == null || String.valueOf(raw).isBlank() ? null : String.valueOf(raw).trim();
        }
        return new AsyncTaskCreateRequest(task.taskId(), "agent.task", input, webhookUrl);
    }

    private String workerId() {
        String configured = properties.getWorkerId();
        return configured == null || configured.isBlank() ? "agent-service" : configured.trim();
    }

    private static Map<String, Object> withoutWebhook(Map<String, Object> input) {
        if (!input.containsKey("webhookUrl")) {
            return input;
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>(input);
        copy.remove("webhookUrl");
        return Map.copyOf(copy);
    }

    private static AsyncTaskStatus status(AgentTaskStatus status) {
        return AsyncTaskStatus.valueOf(status.name());
    }

    @SuppressWarnings("unchecked")
    private static AgentAsyncTask toAgentTask(AsyncTask task) {
        Map<String, Object> input = new LinkedHashMap<>(task.input());
        input.put("asyncTaskKind", task.kind());
        return new AgentAsyncTask(
                task.taskId(),
                task.tenantId(),
                task.userId(),
                AgentTaskStatus.valueOf(task.status().name()),
                Map.copyOf(input),
                task.result(),
                task.error(),
                task.createdAt() == null ? Instant.EPOCH : task.createdAt(),
                task.updatedAt() == null ? Instant.EPOCH : task.updatedAt(),
                task.finishedAt());
    }
}
