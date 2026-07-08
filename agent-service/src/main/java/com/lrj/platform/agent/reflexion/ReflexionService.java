package com.lrj.platform.agent.reflexion;

import com.lrj.platform.agent.async.AgentTaskProgressSink;
import com.lrj.platform.agent.critique.CritiqueAggregation;
import com.lrj.platform.agent.dag.AgentDagCritic;
import com.lrj.platform.protocol.agent.AgentDagCritique;
import com.lrj.platform.protocol.agent.ReflexionAttempt;
import com.lrj.platform.protocol.agent.ReflexionReply;
import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reflexion 自省环（Anthropic Evaluator-Optimizer 模式）：对<strong>同一个答案</strong>反复
 * {@code critique → improve → critique}，加权聚合分达阈值即停，或用尽最大轮次。
 *
 * <p>作为 {@code DeepAgentService} 的同级 sibling 编排器（不塞进 ReAct 内部）。与 DAG（把不同子任务并行）
 * 互补：这里是单答案纵向打磨。评分复用 DAG 的 {@link AgentDagCritic} + {@link AgentDagCritique}
 * （不另造第二套评分器）；加权聚合复用共享 {@link CritiqueAggregation}——DAG replan 与本环同吃一份算法。
 *
 * <p>进度经 {@link AgentTaskProgressSink} emit 阶段事件（{@code attempt-start} / {@code answer}
 * / {@code critique} / {@code done}），SSE 端点把它桥接到 {@code SseEmitter}，对齐单体
 * {@code /chat/reflexive/stream} 的分阶段推进风格。
 */
@Service
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class ReflexionService {

    private static final Logger log = LoggerFactory.getLogger(ReflexionService.class);

    private final ReflexionAnswerer answerer;
    private final AgentDagCritic critic;
    private final ReflexionProperties props;

    public ReflexionService(ReflexionAnswerer answerer, AgentDagCritic critic, ReflexionProperties props) {
        this.answerer = answerer;
        this.critic = critic;
        this.props = props;
    }

    public ReflexionReply reflect(String question) {
        return reflect(question, AgentTaskProgressSink.noop());
    }

    public ReflexionReply reflect(String question, AgentTaskProgressSink progress) {
        String q = question == null ? "" : question.trim();
        List<ReflexionAttempt> attempts = new ArrayList<>();

        progress.emit("attempt-start", Map.of("n", 1));
        String answer = safe(answerer.answer(q));
        progress.emit("answer", Map.of("n", 1, "answer", answer));
        AgentDagCritique c = critic.critique(q, answer);
        double agg = aggregate(c);
        ReflexionAttempt attempt = toAttempt(1, answer, c, agg);
        attempts.add(attempt);
        progress.emit("critique", attempt);
        log.info("reflexion attempt 1 agg={} corr={} comp={} clar={} issue={}",
                agg, c.correctness(), c.completeness(), c.clarity(), c.mainIssue());

        int n = 1;
        while (agg < props.getThreshold() && n < props.getMaxAttempts() + 1) {
            n++;
            progress.emit("attempt-start", Map.of("n", n));
            answer = safe(answerer.improve(q, answer, buildImproveHint(c)));
            progress.emit("answer", Map.of("n", n, "answer", answer));
            c = critic.critique(q, answer);
            agg = aggregate(c);
            attempt = toAttempt(n, answer, c, agg);
            attempts.add(attempt);
            progress.emit("critique", attempt);
            log.info("reflexion attempt {} agg={} issue={}", n, agg, c.mainIssue());
        }

        ReflexionReply reply = new ReflexionReply(
                q, answer, attempts, agg >= props.getThreshold(), TenantContext.current().tenantId());
        progress.emit("done", reply);
        return reply;
    }

    private double aggregate(AgentDagCritique c) {
        return CritiqueAggregation.aggregate(props.getWeights(), c.correctness(), c.completeness(), c.clarity());
    }

    /** 把分数和 mainIssue 揉进一个 hint，让 improve 知道「哪些维度差 + 具体该改什么」。 */
    private String buildImproveHint(AgentDagCritique c) {
        return String.format(
                "Reviewer scored: correctness=%.2f, completeness=%.2f, clarity=%.2f.%n"
                        + "Top issue to fix: %s",
                c.correctness(), c.completeness(), c.clarity(), c.mainIssue());
    }

    private static ReflexionAttempt toAttempt(int n, String answer, AgentDagCritique c, double agg) {
        return new ReflexionAttempt(n, answer, agg,
                c.correctness(), c.completeness(), c.clarity(), c.mainIssue());
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
