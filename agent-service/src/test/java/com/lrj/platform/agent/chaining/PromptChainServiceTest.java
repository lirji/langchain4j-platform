package com.lrj.platform.agent.chaining;

import com.lrj.platform.protocol.agent.ChainRunReply;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PromptChainServiceTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void runsStepsSequentiallyFeedingEachOutputToTheNext() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        List<String> seenInputs = new ArrayList<>();
        // 链节把「上一步输出」记录下来，再原样加一层后缀，验证顺序喂入。
        PromptChainService service = new PromptChainService((instruction, input) -> {
            seenInputs.add(input);
            return input + "->" + instruction;
        });

        ChainRunReply reply = service.run("seed", List.of(
                new ChainStep("s1", "a", 0, null, null),
                new ChainStep("s2", "b", 0, null, null)));

        // 第二步的输入必须是第一步的输出（顺序依赖、纯 transform）。
        assertThat(seenInputs).containsExactly("seed", "seed->a");
        assertThat(reply.completed()).isTrue();
        assertThat(reply.finalOutput()).isEqualTo("seed->a->b");
        assertThat(reply.steps()).extracting("name").containsExactly("s1", "s2");
        assertThat(reply.steps()).allMatch(step -> step.gatePassed());
        assertThat(reply.tenantId()).isEqualTo("acme");
    }

    @Test
    void shortCircuitsWhenGateFailsAndSkipsRemainingSteps() {
        int[] calls = {0};
        PromptChainService service = new PromptChainService((instruction, input) -> {
            calls[0]++;
            return "hi"; // 长度 2，触发第一步的 min-length gate
        });

        ChainRunReply reply = service.run("seed", List.of(
                new ChainStep("gated", "produce", 10, null, null),
                new ChainStep("never", "should-not-run", 0, null, null)));

        // gate 未过即短路：第二步的链节根本没被调用。
        assertThat(calls[0]).isEqualTo(1);
        assertThat(reply.completed()).isFalse();
        assertThat(reply.finalOutput()).isEqualTo("hi");
        assertThat(reply.steps()).hasSize(1);
        assertThat(reply.steps().get(0).gatePassed()).isFalse();
        assertThat(reply.steps().get(0).gateReason()).contains("过短");
    }

    @Test
    void passesWhenContainsAndMatchGatesAreSatisfied() {
        PromptChainService service = new PromptChainService((instruction, input) -> "结论：订单号 A123 已退款");

        ChainRunReply reply = service.run("seed", List.of(
                new ChainStep("check", "summarize", 5, "结论", "[A-Z]\\d{3}")));

        assertThat(reply.completed()).isTrue();
        assertThat(reply.steps().get(0).gatePassed()).isTrue();
        assertThat(reply.steps().get(0).gateReason()).isNull();
        assertThat(reply.finalOutput()).isEqualTo("结论：订单号 A123 已退款");
    }
}
