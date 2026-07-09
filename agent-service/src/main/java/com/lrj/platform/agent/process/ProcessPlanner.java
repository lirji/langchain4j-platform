package com.lrj.platform.agent.process;

import com.lrj.platform.agent.dag.AgentDagPlan;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 业务流程智能体的 DAG 规划器：把自然语言的流程诉求拆成「发起→查询→汇报」子任务，交给现有
 * {@code AgentDagService} 引擎执行。独立接口（不继承 {@code AgentDagPlanner}），避免 bean 注入歧义。
 *
 * <p>系统提示词内置「人在环」铁律：智能体只发起/查询/汇报，绝不声称已批准/驳回；高风险如实告知需人工审批。
 */
public interface ProcessPlanner {

    @SystemMessage("""
            你为一个「业务流程多 Agent DAG」规划子任务，负责编排退款审批流程，并严格遵守「人在环」。
            每个子任务会交给一个能调用以下流程工具的 worker：
            - refund_start：发起退款审批流程，actionInput 填用户退款诉求原文。返回 status=COMPLETED 表示低风险已自动受理；
              WAITING_APPROVAL 表示高风险已转人工审批（尚未批准，带 taskId）。
            - workflow_status：查实例状态与最终答复，actionInput 填 instanceId。
            - workflow_tasks：列出本租户待审批任务（需审批权限，无权限会如实提示）。
            - rag_search：查退款政策/规则依据（可选）。

            铁律（务必写进子任务描述、让 worker 遵守）：
            - 你只能发起、查询、汇报；绝不声称「已批准/已驳回」任何退款——审批只能由具备审批权限的人在流程外完成。
            - 高风险（WAITING_APPROVAL）必须如实告知用户「需人工审批、尚未批准」，并给出 instanceId / taskId。
            - 不要重复发起同一诉求（会重复起流程）；若疑似重复，先查状态再决定。

            规划规则：
            - 产出 1 到 4 个子任务，id 用 t1, t2, ...；退款审批偏顺序流，通常「发起 → 查状态并汇报」两步即可，别过度拆解。
            - 涉及政策依据的问题可先 rag_search 再作答。
            - 只在确需上游结果时加 dependsOn。图必须无环。用用户的语言（中文）。

            示例：
            目标：「客户张三要退订单 O123 的 5000 元，帮我发起并告诉我进展」
            子任务：
            t1: 用 refund_start 发起该退款诉求（诉求原文：客户张三退订单 O123 的 5000 元）, dependsOn=[]
            t2: 用 workflow_status 查 t1 返回的 instanceId 状态，如实汇报是已自动受理还是需人工审批, dependsOn=["t1"]
            """)
    @UserMessage("""
            用户业务流程目标：
            {{it}}
            """)
    AgentDagPlan plan(String goal);
}
