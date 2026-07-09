package com.lrj.platform.agent.actions;

import com.lrj.platform.agent.client.WorkflowClient;
import com.lrj.platform.protocol.workflow.WorkflowInstanceReply;
import com.lrj.platform.protocol.workflow.WorkflowStartReply;
import com.lrj.platform.protocol.workflow.WorkflowTaskView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务流程动作渲染 + 人在环治理断言：refund_start 高风险时明确标注「已转人工、尚未批准」；
 * workflow_tasks 无权限时透传 403 的中文提示。WorkflowClient 用匿名桩，不触真实 HTTP。
 */
class WorkflowActionsTest {

    @Test
    void refundStart_completedRendersStatusAndReply() {
        RefundStartAction action = new RefundStartAction(new StubWorkflowClient() {
            @Override
            public StartOutcome startRefund(String message) {
                return new StartOutcome(
                        new WorkflowStartReply("pi-1", "COMPLETED", "已为您退款", null, "LOW", false), null);
            }
        });

        String output = action.run("退个小额订单");

        assertThat(output).contains("instanceId: pi-1");
        assertThat(output).contains("status: COMPLETED");
        assertThat(output).contains("reply: 已为您退款");
        assertThat(output).doesNotContain("已转人工");
    }

    @Test
    void refundStart_waitingApprovalFlagsHumanInTheLoop() {
        RefundStartAction action = new RefundStartAction(new StubWorkflowClient() {
            @Override
            public StartOutcome startRefund(String message) {
                return new StartOutcome(
                        new WorkflowStartReply("pi-2", "WAITING_APPROVAL", null, "task-9", "HIGH", false), null);
            }
        });

        String output = action.run("退一笔大额订单");

        assertThat(output).contains("status: WAITING_APPROVAL");
        assertThat(output).contains("taskId: task-9");
        assertThat(output).contains("已转人工审批，尚未批准");
    }

    @Test
    void refundStart_errorPropagates() {
        RefundStartAction action = new RefundStartAction(new StubWorkflowClient() {
            @Override
            public StartOutcome startRefund(String message) {
                return new StartOutcome(null, "workflow action disabled");
            }
        });

        assertThat(action.run("退款")).contains("发起失败：").contains("disabled");
    }

    @Test
    void workflowStatus_rendersStatusAndReply() {
        WorkflowStatusAction action = new WorkflowStatusAction(new StubWorkflowClient() {
            @Override
            public InstanceOutcome instance(String instanceId) {
                return new InstanceOutcome(new WorkflowInstanceReply(instanceId, "COMPLETED", "已退款"), null);
            }
        });

        String output = action.run("pi-1");

        assertThat(output).contains("status: COMPLETED");
        assertThat(output).contains("reply: 已退款");
    }

    @Test
    void workflowTasks_rendersList() {
        WorkflowTasksAction action = new WorkflowTasksAction(new StubWorkflowClient() {
            @Override
            public TasksOutcome tasks() {
                return new TasksOutcome(List.of(
                        new WorkflowTaskView("task-9", "审批退款", "pi-2", "HIGH", "退5000", null)), null);
            }
        });

        String output = action.run("");

        assertThat(output).contains("待审批任务：");
        assertThat(output).contains("taskId=task-9");
        assertThat(output).contains("priority=HIGH");
    }

    @Test
    void workflowTasks_forbiddenPassesThroughPermissionNotice() {
        WorkflowTasksAction action = new WorkflowTasksAction(new StubWorkflowClient() {
            @Override
            public TasksOutcome tasks() {
                return new TasksOutcome(List.of(), "当前身份无审批权限（需 approve scope），无法查看待办。");
            }
        });

        assertThat(action.run("")).contains("无审批权限");
    }

    /** 默认三方法抛异常的 {@link WorkflowClient} 桩，各测试按需只覆盖用到的方法。 */
    private abstract static class StubWorkflowClient implements WorkflowClient {
        @Override
        public StartOutcome startRefund(String message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InstanceOutcome instance(String instanceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TasksOutcome tasks() {
            throw new UnsupportedOperationException();
        }
    }
}
