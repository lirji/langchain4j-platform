package com.lrj.platform.agent.async;

import com.lrj.platform.protocol.agent.AgentRunRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class AgentTaskController {

    private final AgentAsyncTaskService tasks;
    private final AgentTaskSseService sse;

    public AgentTaskController(AgentAsyncTaskService tasks, AgentTaskSseService sse) {
        this.tasks = tasks;
        this.sse = sse;
    }

    @PostMapping("/agent/run/async")
    public ResponseEntity<?> runAsync(@RequestBody AgentRunRequest request) {
        if (request == null || request.goal() == null || request.goal().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "goal is required"));
        }
        return ResponseEntity.accepted().body(tasks.submit(request.goal(), request.webhookUrl()));
    }

    @GetMapping("/agent/tasks")
    public List<AgentAsyncTask> listMine() {
        return tasks.listMine();
    }

    @GetMapping("/agent/tasks/{taskId}")
    public ResponseEntity<AgentAsyncTask> get(@PathVariable String taskId) {
        return tasks.get(taskId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/agent/tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String taskId) {
        boolean cancelled = tasks.cancel(taskId);
        if (!cancelled) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("taskId", taskId, "cancelled", true));
    }

    @GetMapping(value = "/agent/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable String taskId) {
        return sse.subscribe(taskId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
