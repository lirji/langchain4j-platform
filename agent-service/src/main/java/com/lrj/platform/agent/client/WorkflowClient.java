package com.lrj.platform.agent.client;

import com.lrj.platform.protocol.workflow.WorkflowInstanceReply;
import com.lrj.platform.protocol.workflow.WorkflowStartReply;
import com.lrj.platform.protocol.workflow.WorkflowTaskView;

import java.util.List;

/**
 * agent→workflow-service 客户端。业务流程智能体经此发起/查询退款审批流程。
 * 错误进返回值（{@code error} 非空）而非抛异常，避免打断 ReAct 循环。
 *
 * <p>治理边界：只暴露发起（start）、查实例（instance）、列待办（tasks）——<strong>不暴露审批（complete）</strong>，
 * 审批是不可逆高风险操作，须由具备 {@code approve} scope 的人在流程外完成。
 */
public interface WorkflowClient {

    /** 发起退款审批流程；message 为用户诉求原文。 */
    StartOutcome startRefund(String message);

    /** 查询实例状态与最终答复；实例不存在时 error 非空。 */
    InstanceOutcome instance(String instanceId);

    /** 列出本租户待审批任务；无 approve 权限时 error 为翻译后的中文提示、tasks 为空。 */
    TasksOutcome tasks();

    record StartOutcome(WorkflowStartReply reply, String error) {
    }

    record InstanceOutcome(WorkflowInstanceReply reply, String error) {
    }

    record TasksOutcome(List<WorkflowTaskView> tasks, String error) {

        public TasksOutcome {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }
}
