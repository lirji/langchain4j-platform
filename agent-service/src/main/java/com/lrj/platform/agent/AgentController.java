package com.lrj.platform.agent;

import com.lrj.platform.protocol.agent.AgentRunReply;
import com.lrj.platform.protocol.agent.AgentRunRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class AgentController {

    private final DeepAgentService agent;

    public AgentController(DeepAgentService agent) {
        this.agent = agent;
    }

    @PostMapping("/agent/run")
    public ResponseEntity<?> run(@RequestBody AgentRunRequest request) {
        if (request.goal() == null || request.goal().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "goal is required"));
        }
        DeepAgentService.Run run = agent.run(request.goal());
        return ResponseEntity.ok(AgentRunMapper.toReply(run));
    }
}
