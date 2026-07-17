package com.lrj.platform.workflow;

import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskCreateRequest;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatusUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流终态回推到 async-task-service 的通知器：审批流程走到 COMPLETED 时，先以 {@code workflow-<instanceId>}
 * 为幂等键创建异步任务（已存在的 409 视为幂等成功），再将其状态置为 SUCCEEDED 并携带 reply/webhookUrl，
 * 由 async-task-service 负责最终 webhook 投递。仅在 {@code app.workflow.enabled=true} 时装配，
 * 通过 {@code workflowAsyncTaskRestTemplate} 调用；任何 {@link org.springframework.web.client.RestClientException}
 * 都被吞掉并返回 false，不阻断主流程。
 */
@Component
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class WorkflowAsyncTaskNotifier {

    private static final Logger log = LoggerFactory.getLogger(WorkflowAsyncTaskNotifier.class);

    private final RestTemplate restTemplate;
    private final WorkflowProperties properties;

    public WorkflowAsyncTaskNotifier(@Qualifier("workflowAsyncTaskRestTemplate") RestTemplate restTemplate,
                                     WorkflowProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public boolean publishTerminal(String instanceId, String tenantId, String webhookUrl, Object reply) {
        String taskId = taskId(instanceId);
        try {
            create(taskId, instanceId, tenantId, webhookUrl);
            return markSucceeded(taskId, instanceId, tenantId, reply);
        } catch (RestClientException ex) {
            log.warn("workflow terminal async-task notification failed instanceId={} taskId={}: {}",
                    instanceId, taskId, ex.toString());
            return false;
        }
    }

    private void create(String taskId, String instanceId, String tenantId, String webhookUrl) {
        try {
            restTemplate.postForEntity("/async/tasks", createRequest(taskId, instanceId, tenantId, webhookUrl), AsyncTask.class);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == 409) {
                log.info("workflow terminal async task already exists instanceId={} taskId={}", instanceId, taskId);
                return;
            }
            throw ex;
        }
    }

    private boolean markSucceeded(String taskId, String instanceId, String tenantId, Object reply) {
        ResponseEntity<Void> response = restTemplate.exchange(
                "/async/tasks/{taskId}/status",
                HttpMethod.PATCH,
                new HttpEntity<>(new AsyncTaskStatusUpdateRequest(
                        AsyncTaskStatus.SUCCEEDED,
                        result(instanceId, tenantId, reply),
                        null)),
                Void.class,
                taskId);
        return response.getStatusCode().is2xxSuccessful();
    }

    private AsyncTaskCreateRequest createRequest(String taskId, String instanceId, String tenantId, String webhookUrl) {
        return new AsyncTaskCreateRequest(
                taskId,
                properties.getTerminalNotification().getAsyncTaskKind(),
                input(instanceId, tenantId),
                webhookUrl);
    }

    private static Map<String, Object> input(String instanceId, String tenantId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("instanceId", instanceId);
        input.put("tenantId", tenantId);
        input.put("status", WorkflowService.STATUS_COMPLETED);
        return Map.copyOf(input);
    }

    private static Map<String, Object> result(String instanceId, String tenantId, Object reply) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("tenantId", tenantId);
        result.put("status", WorkflowService.STATUS_COMPLETED);
        result.put("reply", reply);
        return Map.copyOf(result);
    }

    static String taskId(String instanceId) {
        return "workflow-" + instanceId;
    }
}
