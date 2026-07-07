package com.lrj.platform.interop.a2a;

import com.lrj.platform.protocol.agent.AgentTaskView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * agent-service {@link AgentTaskView} ↔ A2A {@link A2aTask} 的纯函数翻译。无状态、无副作用。
 */
@Component
public class A2aTaskMapper {

    /** agent 状态机字符串 → A2A 状态。未知/空按 UNKNOWN 兜底。 */
    public TaskState toTaskState(String status) {
        if (status == null) {
            return TaskState.UNKNOWN;
        }
        return switch (status) {
            case "PENDING" -> TaskState.SUBMITTED;
            case "RUNNING" -> TaskState.WORKING;
            case "SUCCEEDED" -> TaskState.COMPLETED;
            case "FAILED" -> TaskState.FAILED;
            case "CANCELLED" -> TaskState.CANCELED;
            default -> TaskState.UNKNOWN;
        };
    }

    /**
     * {@link AgentTaskView} → {@link A2aTask}。终态 SUCCEEDED 把结果摊成 text artifact；
     * FAILED 把错误信息挂到 status.message，方便客户端看到失败原因。
     */
    public A2aTask toA2aTask(AgentTaskView task) {
        TaskState state = toTaskState(task.status());
        String ts = firstNonBlank(task.updatedAt(), task.createdAt(), Instant.now().toString());

        A2aMessage statusMsg = null;
        if ("FAILED".equals(task.status()) && task.error() != null && !task.error().isBlank()) {
            statusMsg = A2aMessage.agentText(task.error(), task.taskId(), task.taskId());
        }

        List<Artifact> artifacts = null;
        if ("SUCCEEDED".equals(task.status())) {
            String text = renderResult(task.result());
            if (text != null && !text.isBlank()) {
                artifacts = List.of(Artifact.text("answer", text));
            }
        }

        return new A2aTask(
                task.taskId(),
                task.taskId(),                       // contextId 复用 taskId（无独立会话归并）
                new A2aTaskStatus(state, statusMsg, ts),
                artifacts,
                null);
    }

    /** 把业务结果摊成纯文本。AgentRunReply 结构取 finalAnswer，其它走 toString 兜底。 */
    public String renderResult(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof java.util.Map<?, ?> map) {
            Object finalAnswer = map.get("finalAnswer");
            if (finalAnswer instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return result.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return Instant.now().toString();
    }
}
