package com.lrj.platform.agent;

import dev.langchain4j.model.output.structured.Description;

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
