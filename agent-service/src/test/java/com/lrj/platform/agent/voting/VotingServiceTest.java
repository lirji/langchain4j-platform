package com.lrj.platform.agent.voting;

import com.lrj.platform.protocol.agent.VoteReply;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VotingServiceTest：验证 {@link VotingService} 多投票者集成的两种策略——
 * majority 多数表决（归一化后同派统计、达到 minAgreement 才置信、保序取胜出派原文）、
 * synthesis 聚合综合（agreement 为 NaN 且恒置信），以及按 n 精确 fan-out 投票者调用次数。
 */
class VotingServiceTest {

    @Test
    void invalidCandidateCountFailsBeforeAnyModelCall() {
        AtomicInteger calls = new AtomicInteger();
        VotingProperties props = majorityProps(3, 0.5);
        VotingService service = service(question -> {
            calls.incrementAndGet();
            return "answer";
        }, null, props);

        assertThatThrownBy(() -> service.vote("题", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 10");
        assertThatThrownBy(() -> service.vote("题", 11))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void majorityReachesConsensusOnDiscreteAnswersWhenAgreementMet() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        // 离散题：4 个投票者中 3 个答 "yes"（归一化后同派），1 个答 "no"。
        AtomicInteger seq = new AtomicInteger();
        Voter voter = q -> switch (seq.getAndIncrement()) {
            case 3 -> "No"; // 大小写/空白归一化后仍与 "yes" 不同派
            default -> " YES ";
        };
        VotingService service = service(voter, null, majorityProps(4, 0.5));

        VoteReply reply = service.vote("是否合规？");

        assertThat(reply.strategy()).isEqualTo("majority");
        assertThat(reply.votes()).hasSize(4);
        assertThat(reply.decision()).isEqualTo(" YES "); // 保序取胜出派第一个出现的原文
        assertThat(reply.agreement()).isEqualTo(0.75);
        assertThat(reply.confident()).isTrue();
        assertThat(reply.tenantId()).isEqualTo("acme");
    }

    @Test
    void majorityIsNotConfidentWhenTopShareBelowMinAgreement() {
        // 3 票各不相同 → 最高票 1/3 ≈ 0.33 < minAgreement 0.5 → 不达标。
        AtomicInteger seq = new AtomicInteger();
        Voter voter = q -> "answer-" + seq.getAndIncrement();
        VotingService service = service(voter, null, majorityProps(3, 0.5));

        VoteReply reply = service.vote("自由文本题");

        assertThat(reply.strategy()).isEqualTo("majority");
        assertThat(reply.agreement()).isEqualTo(1.0 / 3.0);
        assertThat(reply.confident()).isFalse();
    }

    @Test
    void synthesisMergesVotesViaAggregator() {
        VotingProperties props = new VotingProperties();
        props.setN(3);
        props.setStrategy(VotingProperties.Strategy.SYNTHESIS);
        Voter voter = q -> "partial";
        VoteAggregator aggregator = (question, answers) -> "merged-consensus";
        VotingService service = service(voter, aggregator, props);

        VoteReply reply = service.vote("综合题");

        assertThat(reply.strategy()).isEqualTo("synthesis");
        assertThat(reply.votes()).hasSize(3);
        assertThat(reply.decision()).isEqualTo("merged-consensus");
        assertThat(reply.agreement()).isNaN();
        assertThat(reply.confident()).isTrue();
    }

    @Test
    void fansOutExactlyNVoterCalls() {
        AtomicInteger calls = new AtomicInteger();
        Voter voter = q -> {
            calls.incrementAndGet();
            return "x";
        };
        VotingService service = service(voter, null, majorityProps(5, 0.5));

        VoteReply reply = service.vote("题", 5);

        assertThat(calls.get()).isEqualTo(5);
        assertThat(reply.votes()).hasSize(5);
    }

    private static VotingProperties majorityProps(int n, double minAgreement) {
        VotingProperties props = new VotingProperties();
        props.setN(n);
        props.setStrategy(VotingProperties.Strategy.MAJORITY);
        props.setMinAgreement(minAgreement);
        return props;
    }

    private static VotingService service(Voter voter, VoteAggregator aggregator, VotingProperties props) {
        // Runnable::run = 同步 executor，确定性单测（生产走 agentTaskExecutor 真并行 fan-out）。
        return new VotingService(voter, aggregator, props, Runnable::run);
    }
}
