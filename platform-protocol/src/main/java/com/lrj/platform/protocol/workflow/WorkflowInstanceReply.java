package com.lrj.platform.protocol.workflow;

/**
 * agent→workflow 查询实例状态的响应契约。字段与 {@code WorkflowService.InstanceView} 对齐。
 */
public record WorkflowInstanceReply(String instanceId, String status, String reply) {
}
