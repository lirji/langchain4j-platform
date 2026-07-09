package com.lrj.platform.agent.actions;

import com.lrj.platform.agent.AgentAction;
import com.lrj.platform.agent.client.WorkflowClient;
import com.lrj.platform.protocol.workflow.WorkflowStartReply;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 发起退款审批流程的 agent 动作。双门控默认关（有副作用，不进通用工具集，防误发起）。
 * 人在环：本动作只「发起」，返回 WAITING_APPROVAL 时如实标注「已转人工、尚未批准」；审批不在 agent 能力内。
 */
@Component
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.workflow.enabled"}, havingValue = "true")
public class RefundStartAction implements AgentAction {

    private final WorkflowClient workflow;

    public RefundStartAction(WorkflowClient workflow) {
        this.workflow = workflow;
    }

    @Override
    public String name() {
        return "refund_start";
    }

    @Override
    public String description() {
        return "发起退款审批流程；actionInput 填用户的退款诉求原文。返回 status=COMPLETED 表示低风险已自动受理，"
                + "WAITING_APPROVAL 表示高风险已转人工审批（尚未批准）。不要重复发起同一诉求。";
    }

    @Override
    public String run(String input) {
        if (input == null || input.isBlank()) {
            return "诉求为空：actionInput 请填用户的退款诉求原文。";
        }
        WorkflowClient.StartOutcome outcome = workflow.startRefund(input.trim());
        if (outcome.error() != null) {
            return "发起失败：" + outcome.error();
        }
        WorkflowStartReply r = outcome.reply();
        StringBuilder sb = new StringBuilder();
        sb.append("instanceId: ").append(r.instanceId()).append('\n');
        sb.append("status: ").append(r.status());
        if (r.priority() != null && !r.priority().isBlank()) {
            sb.append("\npriority: ").append(r.priority());
        }
        if (r.taskId() != null && !r.taskId().isBlank()) {
            sb.append("\ntaskId: ").append(r.taskId());
        }
        if (r.reply() != null && !r.reply().isBlank()) {
            sb.append("\nreply: ").append(r.reply());
        }
        if ("WAITING_APPROVAL".equals(r.status())) {
            sb.append("\n注意：高风险，已转人工审批，尚未批准；审批须由具备审批权限的人在流程外完成。");
        }
        if (r.deduplicated()) {
            sb.append("\n（该诉求此前已发起过，返回的是已存在的流程，未重复发起。）");
        }
        return sb.toString();
    }
}
