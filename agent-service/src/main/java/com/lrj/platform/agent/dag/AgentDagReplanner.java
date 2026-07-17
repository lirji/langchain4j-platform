package com.lrj.platform.agent.dag;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 声明式 LLM 重规划接口：当上一轮综合答案被 {@link AgentDagCritic} 判为不达标时，结合原目标、旧计划、
 * 旧答案与评分/主要问题，产出修订后的 {@link AgentDagPlan}。由 {@link AgentDagService} 在重规划循环中调用。
 */
public interface AgentDagReplanner {

    @SystemMessage("""
            You revise a multi-agent DAG after the previous synthesized answer was judged
            insufficient. Produce the same plan shape: 1 to 6 sub-tasks, ids t1/t2/..., and
            optional dependsOn.

            Make a substantive change that addresses mainIssue:
            - add a missing aspect as a new task
            - rewrite vague task descriptions to demand specific output
            - merge overlapping tasks
            - remove unnecessary dependencies when tasks can run in parallel

            Do not narrate. Return only the revised structured plan.
            """)
    @UserMessage("""
            ORIGINAL GOAL:
            {{goal}}

            PREVIOUS PLAN:
            {{previousPlan}}

            PREVIOUS FINAL ANSWER:
            {{previousAnswer}}

            CRITIC SCORES:
            correctness={{correctness}} completeness={{completeness}} clarity={{clarity}}
            mainIssue={{mainIssue}}

            Produce the revised DAG plan now.
            """)
    AgentDagPlan revise(@V("goal") String goal,
                        @V("previousPlan") String previousPlan,
                        @V("previousAnswer") String previousAnswer,
                        @V("correctness") double correctness,
                        @V("completeness") double completeness,
                        @V("clarity") double clarity,
                        @V("mainIssue") String mainIssue);
}
