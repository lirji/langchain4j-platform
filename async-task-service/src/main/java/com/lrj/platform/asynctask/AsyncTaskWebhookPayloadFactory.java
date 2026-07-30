package com.lrj.platform.asynctask;

import com.lrj.platform.protocol.asynctask.AsyncTask;
import org.springframework.http.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Preserves the legacy ten-field Agent task webhook body while leaving other task kinds unchanged.
 */
final class AsyncTaskWebhookPayloadFactory {

    private static final Set<String> AGENT_KINDS = Set.of(
            "agent.run", "agent.dag", "agent.dag-plan", "agent.analyst", "agent.process");

    private AsyncTaskWebhookPayloadFactory() {
    }

    static Object payload(AsyncTask task) {
        if (!AGENT_KINDS.contains(task.kind())) {
            return task;
        }
        Map<String, Object> input = new LinkedHashMap<>(task.input());
        if (task.webhookUrl() != null && !task.webhookUrl().isBlank()) {
            input.put("webhookUrl", task.webhookUrl());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.taskId());
        payload.put("tenantId", task.tenantId());
        payload.put("userId", task.userId());
        payload.put("status", task.status());
        payload.put("input", input);
        payload.put("result", task.result());
        payload.put("error", task.error());
        payload.put("createdAt", task.createdAt());
        payload.put("updatedAt", task.updatedAt());
        payload.put("finishedAt", task.finishedAt());
        return payload;
    }

    static HttpHeaders headers(AsyncTask task) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Async-Task-Id", task.taskId());
        headers.set("X-Async-Task-Status", task.status().name());
        headers.set("X-Tenant-Id", task.tenantId());
        if (AGENT_KINDS.contains(task.kind())) {
            headers.set("X-Agent-Task-Id", task.taskId());
            headers.set("X-Agent-Task-Status", task.status().name());
        }
        return headers;
    }
}
