package com.lrj.platform.protocol.workflow;

/**
 * agent→workflow 发起退款流程的响应契约。字段与 workflow-service 的 {@code WorkflowService.StartResult} 对齐
 * （Jackson 按字段名反序列化）。{@code status=COMPLETED} 表示低风险已自动受理，
 * {@code WAITING_APPROVAL} 表示高风险已转人工审批（尚未批准，带 {@code taskId}）。
 */
public record WorkflowStartReply(String instanceId,
                                 String status,
                                 String reply,
                                 String taskId,
                                 String priority,
                                 boolean deduplicated) {
}
