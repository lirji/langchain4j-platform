package com.lrj.platform.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

/**
 * ReAct 自主 Agent 的核心执行引擎。反复调用 {@link AgentBrain} 决策下一步，按 {@link AgentDecision#action()}
 * 分派到注册的 {@link AgentAction}（或 {@code finish} 收尾、{@code delegate} 递归派子 Agent），把观察写回
 * scratchpad/history 直至完成。内建多重停止条件（{@code MAX_STEPS}/{@code TIMEOUT}/{@code BUDGET}/{@code LOOP}
 * 打转检测/{@code CANCELLED}/{@code ERROR}），并在超出 {@code maxScratchpadChars} 时经 {@link ScratchpadSummarizer}
 * 压缩早期笔记。所有阈值来自 {@link AgentProperties}；产出内部 {@link Run}/{@link Step} 记录供 {@link AgentRunMapper} 转 DTO。
 */
public class DeepAgentService {

    private static final Logger log = LoggerFactory.getLogger(DeepAgentService.class);

    static final String FINISH = "finish";
    static final String DELEGATE = "delegate";

    private final AgentBrain brain;
    private final AgentProperties props;
    private final ScratchpadSummarizer summarizer;
    private final LongSupplier clock;
    private final ToIntFunction<String> tokenEstimator;
    private final Map<String, AgentAction> actions = new LinkedHashMap<>();

    public DeepAgentService(AgentBrain brain, List<AgentAction> actions, AgentProperties props) {
        this(brain, actions, props, null);
    }

    public DeepAgentService(AgentBrain brain, List<AgentAction> actions, AgentProperties props,
                            ScratchpadSummarizer summarizer) {
        this(brain, actions, props, summarizer, System::currentTimeMillis, DeepAgentService::approxTokens);
    }

    DeepAgentService(AgentBrain brain, List<AgentAction> actions, AgentProperties props,
                     ScratchpadSummarizer summarizer, LongSupplier clock, ToIntFunction<String> tokenEstimator) {
        this.brain = brain;
        this.props = props;
        this.summarizer = summarizer;
        this.clock = clock;
        this.tokenEstimator = tokenEstimator;
        for (AgentAction action : actions) {
            this.actions.put(action.name().toLowerCase(Locale.ROOT), action);
        }
    }

    static int approxTokens(String value) {
        return value == null || value.isEmpty() ? 0 : (value.length() + 3) / 4;
    }

    public Run run(String goal) {
        try {
            return run(goal, 0);
        } finally {
            for (AgentAction action : actions.values()) {
                if (action instanceof AgentRunListener listener) {
                    try {
                        listener.onRunEnd();
                    } catch (Exception ex) {
                        log.warn("agent run-end cleanup failed for action {}: {}", action.name(), ex.toString());
                    }
                }
            }
            Thread.interrupted();
        }
    }

    Run run(String goal, int depth) {
        List<Step> steps = new ArrayList<>();
        StringBuilder scratchpad = new StringBuilder();
        String actionsDesc = describeActions(depth);
        int loopWindow = Math.max(props.getLoopWindow(), props.getMaxRepeats());
        Deque<String> recentSigs = new ArrayDeque<>();
        long deadline = props.getMaxWallClockMs() > 0 ? clock.getAsLong() + props.getMaxWallClockMs() : 0;
        int tokensUsed = 0;

        for (int n = 1; n <= props.getMaxSteps(); n++) {
            if (Thread.currentThread().isInterrupted()) {
                return new Run(goal, steps, bestEffort(scratchpad), "CANCELLED", depth);
            }
            if (deadline > 0 && clock.getAsLong() >= deadline) {
                return new Run(goal, steps, bestEffort(scratchpad), "TIMEOUT", depth);
            }
            if (props.getMaxTokens() > 0 && tokensUsed >= props.getMaxTokens()) {
                return new Run(goal, steps, bestEffort(scratchpad), "BUDGET", depth);
            }

            String scratch = scratchpadOrNone(scratchpad);
            String history = renderHistory(steps);
            AgentDecision decision = null;
            Exception lastError = null;
            int attempts = 1 + Math.max(0, props.getBrainMaxRetries());
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    decision = brain.decide(goal, actionsDesc, scratch, history);
                    lastError = null;
                    break;
                } catch (Exception ex) {
                    lastError = ex;
                    log.warn("agent brain failed at step {} attempt {}/{} depth={}: {}",
                            n, attempt, attempts, depth, ex.toString());
                    if (attempt < attempts && props.getBrainRetryBackoffMs() > 0) {
                        try {
                            Thread.sleep(props.getBrainRetryBackoffMs());
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return new Run(goal, steps, bestEffort(scratchpad), "CANCELLED", depth);
                        }
                    }
                }
            }
            if (decision == null) {
                steps.add(new Step(n, "", "", "",
                        "(brain error after " + attempts + " attempt(s): "
                                + (lastError == null ? "" : lastError.getMessage()) + ")"));
                return new Run(goal, steps, bestEffort(scratchpad), "ERROR", depth);
            }

            tokensUsed += tokenEstimator.applyAsInt(goal)
                    + tokenEstimator.applyAsInt(actionsDesc)
                    + tokenEstimator.applyAsInt(scratch)
                    + tokenEstimator.applyAsInt(history)
                    + tokenEstimator.applyAsInt(decisionText(decision));

            String action = decision.action() == null ? "" : decision.action().trim();
            appendNote(scratchpad, decision.note());
            if (action.equalsIgnoreCase(FINISH)) {
                return new Run(goal, steps, safe(decision.finalAnswer()), "DONE", depth);
            }

            String sig = action.toLowerCase(Locale.ROOT) + "|" + safe(decision.actionInput());
            recentSigs.addLast(sig);
            while (recentSigs.size() > loopWindow) {
                recentSigs.removeFirst();
            }
            long repeats = recentSigs.stream().filter(sig::equals).count();
            if (repeats >= props.getMaxRepeats()) {
                steps.add(new Step(n, safe(decision.thought()), action, safe(decision.actionInput()),
                        "(stopped: action repeated " + repeats + "x within last " + recentSigs.size()
                                + " steps without progress)"));
                return new Run(goal, steps, bestEffort(scratchpad), "LOOP", depth);
            }

            String observation = dispatch(action, safe(decision.actionInput()), depth);
            tokensUsed += tokenEstimator.applyAsInt(observation);
            steps.add(new Step(n, safe(decision.thought()), action, safe(decision.actionInput()), observation));
        }
        return new Run(goal, steps, bestEffort(scratchpad), "MAX_STEPS", depth);
    }

    private String dispatch(String action, String input, int depth) {
        if (action.isBlank()) {
            return "no action chosen; pick one from the available actions or finish";
        }
        if (action.equalsIgnoreCase(DELEGATE)) {
            if (!props.isAllowDelegation()) {
                return "delegation is disabled";
            }
            if (depth >= props.getMaxDepth()) {
                return "delegation denied: max depth (" + props.getMaxDepth() + ") reached";
            }
            Run sub = run(input, depth + 1);
            return "[sub-agent " + sub.stopReason() + "] " + safe(sub.finalAnswer());
        }
        AgentAction agentAction = actions.get(action.toLowerCase(Locale.ROOT));
        if (agentAction == null) {
            return "unknown action '" + action + "'. choose from: " + String.join(", ", actionNames(depth));
        }
        try {
            return safe(agentAction.run(input));
        } catch (Exception ex) {
            return "action error: " + ex.getMessage();
        }
    }

    private String describeActions(int depth) {
        StringBuilder sb = new StringBuilder();
        for (AgentAction action : actions.values()) {
            sb.append("- ").append(action.name()).append(": ").append(action.description()).append('\n');
        }
        if (props.isAllowDelegation() && depth < props.getMaxDepth()) {
            sb.append("- ").append(DELEGATE).append(": 把独立子目标派给子 Agent 处理；actionInput 写子目标\n");
        }
        sb.append("- ").append(FINISH).append(": 任务已完成，在 finalAnswer 给出最终答案\n");
        return sb.toString();
    }

    private List<String> actionNames(int depth) {
        List<String> names = new ArrayList<>(actions.keySet());
        if (props.isAllowDelegation() && depth < props.getMaxDepth()) {
            names.add(DELEGATE);
        }
        names.add(FINISH);
        return names;
    }

    private String renderHistory(List<Step> steps) {
        if (steps.isEmpty()) {
            return "(暂无)";
        }
        int from = Math.max(0, steps.size() - props.getHistoryWindow());
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < steps.size(); i++) {
            Step step = steps.get(i);
            sb.append(step.n()).append(". ").append(step.action());
            if (!step.actionInput().isBlank()) {
                sb.append("(").append(step.actionInput()).append(")");
            }
            sb.append(" -> ").append(step.observation()).append('\n');
        }
        return sb.toString();
    }

    private void appendNote(StringBuilder scratchpad, String note) {
        if (note == null || note.isBlank()) {
            return;
        }
        if (!scratchpad.isEmpty()) {
            scratchpad.append('\n');
        }
        scratchpad.append("- ").append(note.trim());
        int cap = props.getMaxScratchpadChars();
        if (cap > 0 && scratchpad.length() > cap) {
            compactScratchpad(scratchpad, cap);
        }
    }

    private void compactScratchpad(StringBuilder scratchpad, int cap) {
        String[] lines = scratchpad.toString().split("\n", -1);
        int headroom = summarizer != null ? Math.max(1, cap - cap / 4) : cap;
        Deque<String> kept = new ArrayDeque<>();
        int len = 0;
        int i = lines.length - 1;
        for (; i >= 0; i--) {
            int add = lines[i].length() + 1;
            if (len + add > headroom && !kept.isEmpty()) {
                break;
            }
            kept.addFirst(lines[i]);
            len += add;
        }
        StringBuilder older = new StringBuilder();
        for (int j = 0; j <= i; j++) {
            if (!older.isEmpty()) {
                older.append('\n');
            }
            older.append(lines[j]);
        }
        StringBuilder result = new StringBuilder();
        if (summarizer != null && !older.isEmpty()) {
            try {
                String summary = summarizer.summarize(older.toString());
                if (summary != null && !summary.isBlank()) {
                    result.append("- (早期结论摘要) ").append(summary.trim());
                }
            } catch (Exception ex) {
                log.warn("scratchpad summarize failed, dropping oldest instead: {}", ex.toString());
            }
        }
        for (String line : kept) {
            if (!result.isEmpty()) {
                result.append('\n');
            }
            result.append(line);
        }
        scratchpad.setLength(0);
        scratchpad.append(result);
        while (scratchpad.length() > cap) {
            int nl = scratchpad.indexOf("\n");
            if (nl < 0) {
                scratchpad.setLength(cap);
                break;
            }
            scratchpad.delete(0, nl + 1);
        }
    }

    private static String scratchpadOrNone(StringBuilder scratchpad) {
        return scratchpad.isEmpty() ? "(空)" : scratchpad.toString();
    }

    private static String bestEffort(StringBuilder scratchpad) {
        return scratchpad.isEmpty() ? "" : scratchpad.toString();
    }

    private static String decisionText(AgentDecision decision) {
        return safe(decision.thought()) + safe(decision.action()) + safe(decision.actionInput())
                + safe(decision.note()) + safe(decision.finalAnswer());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record Run(String goal, List<Step> steps, String finalAnswer, String stopReason, int depth) {
        public Run {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    public record Step(int n, String thought, String action, String actionInput, String observation) {
    }
}
