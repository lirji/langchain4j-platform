package com.lrj.platform.agent.actions;

import com.lrj.platform.agent.AgentAction;
import com.lrj.platform.agent.client.WorkflowClient;
import com.lrj.platform.protocol.workflow.WorkflowTaskView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 列出本租户待审批退款任务的 agent 动作。双门控默认关。
 * 天然受权限约束：调用方无 {@code approve} scope 时 workflow-service 返回 403，本动作翻译成中文提示，不越权。
 */
@Component
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.workflow.enabled"}, havingValue = "true")
public class WorkflowTasksAction implements AgentAction {

    private final WorkflowClient workflow;

    public WorkflowTasksAction(WorkflowClient workflow) {
        this.workflow = workflow;
    }

    @Override
    public String name() {
        return "workflow_tasks";
    }

    @Override
    public String description() {
        return "列出本租户待审批的退款任务（需审批权限，无权限会提示）；actionInput 忽略。";
    }

    @Override
    public String run(String input) {
        WorkflowClient.TasksOutcome outcome = workflow.tasks();
        if (outcome.error() != null) {
            return outcome.error();  // 403 已翻译成中文提示
        }
        if (outcome.tasks().isEmpty()) {
            return "当前没有待审批的退款任务。";
        }
        StringBuilder sb = new StringBuilder("待审批任务：");
        for (WorkflowTaskView t : outcome.tasks()) {
            sb.append("\n- taskId=").append(t.taskId())
                    .append(" priority=").append(nz(t.priority()))
                    .append(" summary=").append(nz(t.summary()));
            if (t.assignee() != null && !t.assignee().isBlank()) {
                sb.append(" assignee=").append(t.assignee());
            }
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
