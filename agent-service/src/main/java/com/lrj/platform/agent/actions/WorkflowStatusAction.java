package com.lrj.platform.agent.actions;

import com.lrj.platform.agent.AgentAction;
import com.lrj.platform.agent.client.WorkflowClient;
import com.lrj.platform.protocol.workflow.WorkflowInstanceReply;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 查询工作流实例状态与最终答复的 agent 动作。双门控默认关。只读，供智能体汇报进展。
 */
@Component
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.workflow.enabled"}, havingValue = "true")
public class WorkflowStatusAction implements AgentAction {

    private final WorkflowClient workflow;

    public WorkflowStatusAction(WorkflowClient workflow) {
        this.workflow = workflow;
    }

    @Override
    public String name() {
        return "workflow_status";
    }

    @Override
    public String description() {
        return "查询工作流实例的状态与最终答复；actionInput 填 refund_start 返回的 instanceId。";
    }

    @Override
    public String run(String input) {
        if (input == null || input.isBlank()) {
            return "instanceId 为空：actionInput 请填 refund_start 返回的 instanceId。";
        }
        WorkflowClient.InstanceOutcome outcome = workflow.instance(input.trim());
        if (outcome.error() != null) {
            return "查询失败：" + outcome.error();
        }
        WorkflowInstanceReply r = outcome.reply();
        StringBuilder sb = new StringBuilder();
        sb.append("status: ").append(r.status());
        if (r.reply() != null && !r.reply().isBlank()) {
            sb.append("\nreply: ").append(r.reply());
        } else if ("WAITING_APPROVAL".equals(r.status())) {
            sb.append("\n（仍在等待人工审批，尚无最终答复。）");
        }
        return sb.toString();
    }
}
