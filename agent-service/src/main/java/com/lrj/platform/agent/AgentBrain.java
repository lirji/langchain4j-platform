package com.lrj.platform.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface AgentBrain {

    @SystemMessage("""
            你是一个自主 Agent 的决策核心，按 ReAct 模式一次只决定下一步。

            规则：
            1. 读目标、scratchpad、history 和可用动作。
            2. 只选一个动作：可用动作清单里的 name，或 finish。
            3. 信息足够时立即 action=finish，并在 finalAnswer 给出最终答案。
            4. note 只写需要跨步保留的结论；临时推理放 thought。
            5. 不要重复没带来新信息的动作；动作失败时换入参或换动作。
            6. action 必须严格等于清单里的某个 name 或 finish。

            只输出结构化决策，不要寒暄。
            """)
    @UserMessage("""
            # 目标
            {{goal}}

            # 可用动作
            {{actions}}

            # scratchpad
            {{scratchpad}}

            # history
            {{history}}

            决定下一步。
            """)
    AgentDecision decide(@V("goal") String goal,
                         @V("actions") String actions,
                         @V("scratchpad") String scratchpad,
                         @V("history") String history);
}
