package com.lrj.platform.protocol.workflow;

/**
 * agent→workflow 待审批任务视图。字段与 {@code WorkflowService.TaskView} 对齐。
 * 仅具备 {@code approve} scope 的调用方可列出（否则 workflow-service 返回 403）。
 */
public record WorkflowTaskView(String taskId,
                               String name,
                               String instanceId,
                               String priority,
                               String summary,
                               String assignee) {
}
