package com.lrj.platform.agent.analyst;

import com.lrj.platform.agent.async.AgentAsyncTaskService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 数据分析智能体端点。基于多 Agent DAG 编排：传一个自然语言数据问题（body {@code {"goal": "..."}}），
 * 自动拆解为「探表→取数→计算→解读」并行/依赖子任务，synthesis 收口给出带 SQL 与数字依据的结论。
 * 返回 {@code AgentDagRunReply}（含 levels / 各子任务结果 / synthesis / 可选 critique attempts）。
 */
@RestController
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class DataAnalystController {

    private final DataAnalystService analyst;
    private final AgentAsyncTaskService tasks;

    public DataAnalystController(DataAnalystService analyst, AgentAsyncTaskService tasks) {
        this.analyst = analyst;
        this.tasks = tasks;
    }

    @PostMapping("/agent/analyst/run")
    public ResponseEntity<?> run(@RequestBody(required = false) Map<String, String> request) {
        String goal = request == null ? "" : request.getOrDefault("goal", "");
        if (goal == null || goal.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "goal is required"));
        }
        try {
            return ResponseEntity.ok(analyst.analyze(goal));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/agent/analyst/run/async")
    public ResponseEntity<?> runAsync(@RequestBody(required = false) Map<String, String> request) {
        String goal = request == null ? "" : request.getOrDefault("goal", "");
        if (goal == null || goal.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "goal is required"));
        }
        String webhookUrl = request == null ? null : request.get("webhookUrl");
        return ResponseEntity.accepted().body(tasks.submitWithProgress(
                "AGENT_ANALYST",
                AgentAsyncTaskService.input(Map.of("goal", goal), webhookUrl),
                progress -> analyst.analyze(goal, progress)));
    }
}
