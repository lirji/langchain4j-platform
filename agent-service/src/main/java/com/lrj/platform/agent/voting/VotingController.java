package com.lrj.platform.agent.voting;

import com.lrj.platform.protocol.agent.VoteRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Voting 入口（{@code DeepAgentService} 的同级 sibling 编排器）。走 {@code /agent/**} 同套鉴权链
 * （内部 JWT + 多租户 + 限流 + 配额），与单体 {@code /chat/vote} 行为对齐、端点迁到 {@code /agent/vote}。
 *
 * <p>{@code POST /agent/vote} body {@code {"question":"...","n":5}}（n 可选，默认 {@code app.agent.voting.n}）
 * → 并行跑 N 次 + 聚合，返回 {@code VoteReply}（votes / decision / agreement / confident）。
 */
@RestController
@ConditionalOnBean(VotingService.class)
public class VotingController {

    private final VotingService voting;

    public VotingController(VotingService voting) {
        this.voting = voting;
    }

    @PostMapping("/agent/vote")
    public ResponseEntity<?> vote(@RequestBody VoteRequest request) {
        String question = request == null || request.question() == null ? "" : request.question();
        if (question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }
        Integer n = request.n();
        return ResponseEntity.ok(n != null ? voting.vote(question, n) : voting.vote(question));
    }
}
