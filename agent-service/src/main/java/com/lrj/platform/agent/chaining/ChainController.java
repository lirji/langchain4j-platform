package com.lrj.platform.agent.chaining;

import com.lrj.platform.protocol.agent.ChainRunRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Prompt Chaining 入口（{@code DeepAgentService} 的同级 sibling 编排器）。走 {@code /agent/**} 同套鉴权链
 * （内部 JWT + 多租户 + 限流 + 配额），与单体 {@code /chat/chain} 行为对齐、端点迁到 {@code /agent/chain}。
 *
 * <p>{@code POST /agent/chain} body {@code {"input":"..."}} → 跑 yml 里预定义的默认链
 * （{@code app.agent.chaining.steps}），返回逐步 trace + 是否全程通过。
 */
@RestController
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class ChainController {

    private final PromptChainService chain;
    private final ChainingProperties props;

    public ChainController(PromptChainService chain, ChainingProperties props) {
        this.chain = chain;
        this.props = props;
    }

    @PostMapping("/agent/chain")
    public ResponseEntity<?> chain(@RequestBody ChainRunRequest request) {
        String input = request == null || request.input() == null ? "" : request.input();
        if (input.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "input is required"));
        }
        List<ChainStep> steps = props.getSteps();
        if (steps == null || steps.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "no chain steps configured (app.agent.chaining.steps)"));
        }
        return ResponseEntity.ok(chain.run(input, steps));
    }
}
