package com.lrj.platform.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeepAgentServiceTest：验证 {@link DeepAgentService} 的 ReAct 循环——直接 finish、执行动作并把观察
 * 喂回轨迹、重复动作触发 LOOP 停止，以及 delegate 子 Agent 的深度上限。
 */
class DeepAgentServiceTest {

    @Test
    void finishesWhenBrainChoosesFinish() {
        DeepAgentService service = new DeepAgentService(
                new ScriptedBrain(finish("done")),
                List.of(new EchoAction()),
                props());

        DeepAgentService.Run run = service.run("goal");

        assertThat(run.stopReason()).isEqualTo("DONE");
        assertThat(run.finalAnswer()).isEqualTo("done");
        assertThat(run.steps()).isEmpty();
    }

    @Test
    void executesActionAndFeedsObservationIntoTrace() {
        DeepAgentService service = new DeepAgentService(
                new ScriptedBrain(act("echo", "hello", "noted"), finish("answer")),
                List.of(new EchoAction()),
                props());

        DeepAgentService.Run run = service.run("goal");

        assertThat(run.stopReason()).isEqualTo("DONE");
        assertThat(run.steps()).hasSize(1);
        assertThat(run.steps().getFirst().observation()).isEqualTo("echo:hello");
    }

    @Test
    void stopsRepeatedActionsAsLoop() {
        AgentProperties props = props();
        props.setMaxRepeats(2);
        DeepAgentService service = new DeepAgentService(
                new ScriptedBrain(act("echo", "same", ""), act("echo", "same", "")),
                List.of(new EchoAction()),
                props);

        DeepAgentService.Run run = service.run("goal");

        assertThat(run.stopReason()).isEqualTo("LOOP");
        assertThat(run.steps().getLast().observation()).contains("stopped: action repeated");
    }

    @Test
    void delegateRunsSubAgentUntilDepthCap() {
        AgentProperties props = props();
        props.setMaxDepth(1);
        DeepAgentService service = new DeepAgentService(
                new ScriptedBrain(
                        act("delegate", "sub goal", ""),
                        act("delegate", "too deep", ""),
                        finish("top done")),
                List.of(new EchoAction()),
                props);

        DeepAgentService.Run run = service.run("goal");

        assertThat(run.steps()).hasSize(1);
        assertThat(run.steps().getFirst().observation()).contains("[sub-agent");
    }

    private static AgentDecision act(String action, String input, String note) {
        return new AgentDecision("thought", action, input, note, "");
    }

    private static AgentDecision finish(String answer) {
        return new AgentDecision("done", "finish", "", "", answer);
    }

    private static AgentProperties props() {
        AgentProperties props = new AgentProperties();
        props.setMaxSteps(5);
        props.setMaxRepeats(3);
        props.setLoopWindow(6);
        return props;
    }

    private static class ScriptedBrain implements AgentBrain {
        private final Deque<AgentDecision> script = new ArrayDeque<>();

        ScriptedBrain(AgentDecision... decisions) {
            script.addAll(List.of(decisions));
        }

        @Override
        public AgentDecision decide(String goal, String actions, String scratchpad, String history) {
            AgentDecision decision = script.poll();
            return decision == null ? finish("fallback") : decision;
        }
    }

    private static class EchoAction implements AgentAction {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "echo input";
        }

        @Override
        public String run(String input) {
            return "echo:" + input;
        }
    }
}
