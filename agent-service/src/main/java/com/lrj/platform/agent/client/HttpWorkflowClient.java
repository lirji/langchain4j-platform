package com.lrj.platform.agent.client;

import com.lrj.platform.protocol.workflow.WorkflowInstanceReply;
import com.lrj.platform.protocol.workflow.WorkflowStartReply;
import com.lrj.platform.protocol.workflow.WorkflowTaskView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * HTTP 版 {@link WorkflowClient}，经带租户/trace 透传的 {@code workflowRestTemplate} 调 workflow-service。
 * 双门控 {@code {app.agent.enabled, app.agent.workflow.enabled}}，默认关（发起退款有副作用，非默认开）。
 */
@Component
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.workflow.enabled"}, havingValue = "true")
public class HttpWorkflowClient implements WorkflowClient {

    private static final Logger log = LoggerFactory.getLogger(HttpWorkflowClient.class);

    private final RestTemplate restTemplate;

    public HttpWorkflowClient(@Qualifier("workflowRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public StartOutcome startRefund(String message) {
        try {
            // 出站 body 只带 message：chatId 由 workflow-service 兜底 "default"，不传 dedupeId/webhookUrl。
            WorkflowStartReply reply = restTemplate.postForObject(
                    "/workflow/refund/start", Map.of("message", message), WorkflowStartReply.class);
            return new StartOutcome(reply, reply == null ? "empty workflow response" : null);
        } catch (RestClientException ex) {
            log.warn("agent refund start failed: {}", ex.toString());
            return new StartOutcome(null, ex.getMessage());
        }
    }

    @Override
    public InstanceOutcome instance(String instanceId) {
        try {
            WorkflowInstanceReply reply = restTemplate.getForObject(
                    "/workflow/instances/{id}", WorkflowInstanceReply.class, instanceId);
            return new InstanceOutcome(reply, reply == null ? "empty workflow response" : null);
        } catch (HttpClientErrorException.NotFound nf) {
            return new InstanceOutcome(null, "instance not found: " + instanceId);
        } catch (RestClientException ex) {
            log.warn("agent workflow instance failed: {}", ex.toString());
            return new InstanceOutcome(null, ex.getMessage());
        }
    }

    @Override
    public TasksOutcome tasks() {
        try {
            WorkflowTaskView[] arr = restTemplate.getForObject("/workflow/tasks", WorkflowTaskView[].class);
            return new TasksOutcome(arr == null ? List.of() : List.of(arr), null);
        } catch (HttpClientErrorException.Forbidden f) {
            return new TasksOutcome(List.of(), "当前身份无审批权限（需 approve scope），无法查看待办。");
        } catch (RestClientException ex) {
            log.warn("agent workflow tasks failed: {}", ex.toString());
            return new TasksOutcome(List.of(), ex.getMessage());
        }
    }
}
