package com.lrj.platform.agent.dag;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

public record AgentDagPlan(
        @Description("""
                1 to 6 sub-tasks that together answer the user's goal.
                Use one task for simple goals. Use dependencies only when a task literally needs
                another task's output as input.
                """)
        List<Task> tasks) {

    public AgentDagPlan {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    public record Task(
            @Description("Short stable id like t1, t2, t3")
            String id,

            @Description("Self-contained instruction for the worker")
            String description,

            @Description("Upstream task ids this task needs as context. Empty means it can run in parallel.")
            List<String> dependsOn) {

        public Task {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }
    }
}
