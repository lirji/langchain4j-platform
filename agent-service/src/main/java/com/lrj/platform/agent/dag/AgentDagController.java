package com.lrj.platform.agent.dag;

import com.lrj.platform.agent.async.AgentAsyncTaskService;
import com.lrj.platform.protocol.agent.AgentDagRunRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class AgentDagController {

    private final AgentDagService dag;
    private final AgentAsyncTaskService tasks;

    public AgentDagController(AgentDagService dag, AgentAsyncTaskService tasks) {
        this.dag = dag;
        this.tasks = tasks;
    }

    @PostMapping("/agent/dag/run")
    public ResponseEntity<?> run(@RequestBody AgentDagRunRequest request) {
        if (request == null || request.goal() == null || request.goal().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "goal is required"));
        }
        try {
            return ResponseEntity.ok(dag.run(request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/agent/dag/plan-run")
    public ResponseEntity<?> planAndRun(@RequestBody Map<String, String> request) {
        String goal = request.getOrDefault("goal", "");
        if (goal.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "goal is required"));
        }
        try {
            return ResponseEntity.ok(dag.planAndRun(goal));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/agent/dag/run/async")
    public ResponseEntity<?> runAsync(@RequestBody AgentDagRunRequest request) {
        if (request == null || request.goal() == null || request.goal().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "goal is required"));
        }
        try {
            return ResponseEntity.accepted().body(tasks.submitWithProgress(
                    "AGENT_DAG",
                    AgentAsyncTaskService.input(Map.of("goal", request.goal(), "tasks", request.tasks()), request.webhookUrl()),
                    progress -> dag.run(request, progress)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/agent/dag/plan-run/async")
    public ResponseEntity<?> planAndRunAsync(@RequestBody Map<String, String> request) {
        String goal = request.getOrDefault("goal", "");
        if (goal.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "goal is required"));
        }
        return ResponseEntity.accepted().body(tasks.submitWithProgress(
                "AGENT_DAG_PLAN",
                AgentAsyncTaskService.input(Map.of("goal", goal), request.get("webhookUrl")),
                progress -> dag.planAndRun(goal, progress)));
    }
}
