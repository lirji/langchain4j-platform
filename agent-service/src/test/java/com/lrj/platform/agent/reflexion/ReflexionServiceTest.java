package com.lrj.platform.agent.reflexion;

import com.lrj.platform.agent.dag.AgentDagCritic;
import com.lrj.platform.protocol.agent.AgentDagCritique;
import com.lrj.platform.protocol.agent.ReflexionReply;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReflexionServiceTest：验证 {@link ReflexionService} 反思式自改进的收敛逻辑——
 * 首答即达阈值时提前停止（不调用 improve）、恒不达阈时最多迭代 maxAttempts 次、
 * improve 提示中携带评分与主要问题、以及各阶段进度事件（attempt-start/answer/critique/done）的发射。
 */
class ReflexionServiceTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void stopsAfterFirstAttemptWhenAggregateMeetsThreshold() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        RecordingAnswerer answerer = new RecordingAnswerer("初答", "改进后");
        // 高分 → agg=1.0 >= 0.75，第一轮即收敛，不应调用 improve。
        AgentDagCritic critic = (q, a) -> new AgentDagCritique(1.0, 1.0, 1.0, "n/a");
        ReflexionService service = new ReflexionService(answerer, critic, props(0.75, 2));

        ReflexionReply reply = service.reflect("问题");

        assertThat(reply.attempts()).hasSize(1);
        assertThat(reply.finalAnswer()).isEqualTo("初答");
        assertThat(reply.acceptedByThreshold()).isTrue();
        assertThat(reply.tenantId()).isEqualTo("acme");
        assertThat(answerer.improveCalls).isZero();
    }

    @Test
    void runsUpToMaxAttemptsWhenNeverMeetsThreshold() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        RecordingAnswerer answerer = new RecordingAnswerer("a0", "a1", "a2", "a3");
        // 恒低分 → 永不达阈，总尝试数 = 1 + maxAttempts。
        AgentDagCritic critic = (q, a) -> new AgentDagCritique(0.2, 0.2, 0.2, "still weak");
        ReflexionService service = new ReflexionService(answerer, critic, props(0.75, 2));

        ReflexionReply reply = service.reflect("问题");

        assertThat(reply.attempts()).hasSize(3);                 // 1 初答 + 2 improve
        assertThat(answerer.improveCalls).isEqualTo(2);
        assertThat(reply.acceptedByThreshold()).isFalse();
        assertThat(reply.finalAnswer()).isEqualTo("a2");         // 最后一次 improve 返回
    }

    @Test
    void improveReceivesHintWithScoresAndMainIssue() {
        RecordingAnswerer answerer = new RecordingAnswerer("weak", "better");
        AtomicInteger seq = new AtomicInteger();
        // 第 1 轮低分带具体 issue → 触发 improve；第 2 轮高分收敛。
        AgentDagCritic critic = (q, a) -> seq.getAndIncrement() == 0
                ? new AgentDagCritique(0.5, 0.4, 0.6, "缺少关键步骤")
                : new AgentDagCritique(0.95, 0.95, 0.95, "n/a");
        ReflexionService service = new ReflexionService(answerer, critic, props(0.75, 2));

        ReflexionReply reply = service.reflect("问题");

        assertThat(reply.attempts()).hasSize(2);
        assertThat(reply.acceptedByThreshold()).isTrue();
        assertThat(answerer.lastImproveHint)
                .contains("缺少关键步骤")
                .contains("correctness=0.50")
                .contains("completeness=0.40")
                .contains("clarity=0.60");
        assertThat(answerer.lastImprovePrevious).isEqualTo("weak");
    }

    @Test
    void emitsStageProgressEvents() {
        RecordingAnswerer answerer = new RecordingAnswerer("a0", "a1");
        AtomicInteger seq = new AtomicInteger();
        AgentDagCritic critic = (q, a) -> seq.getAndIncrement() == 0
                ? new AgentDagCritique(0.3, 0.3, 0.3, "weak")
                : new AgentDagCritique(0.9, 0.9, 0.9, "n/a");
        ReflexionService service = new ReflexionService(answerer, critic, props(0.75, 2));
        List<String> events = new ArrayList<>();

        service.reflect("问题", (event, data) -> events.add(event));

        assertThat(events).containsSubsequence("attempt-start", "answer", "critique", "done");
        assertThat(events).filteredOn("done"::equals).hasSize(1);
    }

    private static ReflexionProperties props(double threshold, int maxAttempts) {
        ReflexionProperties p = new ReflexionProperties();
        p.setThreshold(threshold);
        p.setMaxAttempts(maxAttempts);
        return p;
    }

    /** ReflexionAnswerer 有两个抽象方法，用可记录的测试替身而非 mock。 */
    private static final class RecordingAnswerer implements ReflexionAnswerer {
        private final String[] answers;
        private final AtomicInteger idx = new AtomicInteger();
        int improveCalls;
        String lastImproveHint;
        String lastImprovePrevious;

        RecordingAnswerer(String... answers) {
            this.answers = answers;
        }

        @Override
        public String answer(String question) {
            return answers[idx.getAndIncrement()];
        }

        @Override
        public String improve(String question, String previous, String critique) {
            improveCalls++;
            lastImproveHint = critique;
            lastImprovePrevious = previous;
            return answers[idx.getAndIncrement()];
        }
    }
}
