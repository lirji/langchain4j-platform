package com.lrj.platform.agent.dag;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface AgentDagPlanner {

    @SystemMessage("""
            You plan a multi-agent DAG for a user goal.

            Rules:
            - Produce 1 to 6 sub-tasks.
            - Do not over-decompose. For a focused single-aspect goal, produce exactly one task.
            - Split multi-aspect goals by aspect, not by entity.
            - Use ids t1, t2, t3, ...
            - Match the user's language.
            - Dependencies are optional and should be rare.
            - Add dependsOn only when a sub-task literally needs another task's output.
            - Independent tasks must have empty dependsOn and can run in parallel.
            - The graph must be acyclic.

            Good DAG example:
            Goal: "先列出 Java 21 的 3 个重要新特性，然后基于其中最影响并发编程的一个详细解释"
            Tasks:
            t1: 列出 Java 21 的 3 个重要新特性, dependsOn=[]
            t2: 基于 t1 的列表，挑出对并发编程影响最大的特性并详细解释, dependsOn=["t1"]
            """)
    @UserMessage("""
            User goal:
            {{it}}
            """)
    AgentDagPlan plan(String goal);
}
