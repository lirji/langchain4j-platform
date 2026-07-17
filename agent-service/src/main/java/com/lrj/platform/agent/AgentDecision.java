package com.lrj.platform.agent;

import dev.langchain4j.model.output.structured.Description;

/**
 * ReAct 单步决策的结构化输出，由 {@link AgentBrain} 每步返回、被 {@link DeepAgentService} 消费。
 * 每个字段带 {@link Description} 供 LLM 结构化抽取遵循：{@code thought} 临时推理、{@code action} 选中的动作名
 * （清单里的某个 name 或 {@code finish}）、{@code actionInput} 动作入参、{@code note} 需跨步保留的结论、
 * {@code finalAnswer} 完成时的最终答案。
 */
public record AgentDecision(
        @Description("一句话推理：为什么选这个动作、打算干什么")
        String thought,
        @Description("要执行的动作名：必须是可用动作清单里的某个 name；或填 finish 表示任务已完成")
        String action,
        @Description("动作的输入参数；action=finish 时留空")
        String actionInput,
        @Description("要追加到 scratchpad 的持久笔记/已确认结论；无则留空")
        String note,
        @Description("当 action=finish 时给出面向用户的最终答案；否则留空")
        String finalAnswer
) {
}
