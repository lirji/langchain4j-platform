package com.lrj.platform.agent.process;

import com.lrj.platform.agent.async.AgentAsyncTaskService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 业务流程智能体端点。基于多 Agent DAG 编排：传一个自然语言流程诉求（body {@code {"goal": "..."}}），
 * 自动拆解为「发起 → 查询 → 汇报」子任务执行，synthesis 收口如实告知进展（人在环，绝不代替审批）。
 * 返回 {@code AgentDagRunReply}。双门控默认关（{@code AGENT_WORKFLOW_ENABLED=true} 开启）。
 */
@RestController
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.workflow.enabled"}, havingValue = "true")
public class ProcessController {

    private final ProcessService process;
    private final AgentAsyncTaskService tasks;

    public ProcessController(ProcessService process, AgentAsyncTaskService tasks) {
        this.process = process;
        this.tasks = tasks;
    }

    @PostMapping("/agent/process/run")
    public ResponseEntity<?> run(@RequestBody(required = false) Map<String, String> request) {
        String goal = request == null ? "" : request.getOrDefault("goal", "");
        if (goal == null || goal.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "goal is required"));
        }
        try {
            return ResponseEntity.ok(process.run(goal));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/agent/process/run/async")
    public ResponseEntity<?> runAsync(@RequestBody(required = false) Map<String, String> request) {
        String goal = request == null ? "" : request.getOrDefault("goal", "");
        if (goal == null || goal.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "goal is required"));
        }
        String webhookUrl = request == null ? null : request.get("webhookUrl");
        return ResponseEntity.accepted().body(tasks.submitWithProgress(
                "AGENT_PROCESS",
                AgentAsyncTaskService.input(Map.of("goal", goal), webhookUrl),
                progress -> process.run(goal, progress)));
    }
}
