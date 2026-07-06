package com.lrj.platform.agent.dag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.agent.AgentRunMapper;
import com.lrj.platform.agent.DeepAgentService;
import com.lrj.platform.agent.async.AgentTaskProgressSink;
import com.lrj.platform.protocol.agent.AgentDagAttempt;
import com.lrj.platform.protocol.agent.AgentDagCritique;
import com.lrj.platform.protocol.agent.AgentDagRunReply;
import com.lrj.platform.protocol.agent.AgentDagRunRequest;
import com.lrj.platform.protocol.agent.AgentDagTask;
import com.lrj.platform.protocol.agent.AgentDagTaskResult;
import com.lrj.platform.protocol.agent.AgentRunReply;
import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
@ConditionalOnBean(DeepAgentService.class)
public class AgentDagService {

    private static final Logger log = LoggerFactory.getLogger(AgentDagService.class);

    private final DeepAgentService agent;
    private final Executor executor;
    private final AgentDagProperties properties;
    private final AgentDagPlanner planner;
    private final AgentDagCritic critic;
    private final AgentDagReplanner replanner;
    private final ObjectMapper mapper;

    public AgentDagService(DeepAgentService agent,
                           @Qualifier("agentTaskExecutor") Executor executor,
                           AgentDagProperties properties,
                           AgentDagPlanner planner,
                           AgentDagCritic critic,
                           AgentDagReplanner replanner,
                           ObjectMapper mapper) {
        this.agent = agent;
        this.executor = executor;
        this.properties = properties;
        this.planner = planner;
        this.critic = critic;
        this.replanner = replanner;
        this.mapper = mapper;
    }

    public AgentDagRunReply planAndRun(String goal) {
        return planAndRun(goal, AgentTaskProgressSink.noop());
    }

    public AgentDagRunReply planAndRun(String goal, AgentTaskProgressSink progress) {
        AgentDagPlan plan = planner.plan(goal);
        List<AgentDagTask> tasks = plan == null || plan.tasks().isEmpty()
                ? List.of(new AgentDagTask("t1", goal, List.of()))
                : plan.tasks().stream()
                .map(task -> new AgentDagTask(task.id(), task.description(), task.dependsOn()))
                .toList();
        progress.emit("dag-planned", Map.of("goal", goal, "tasks", tasks));
        return run(new AgentDagRunRequest(goal, tasks), progress);
    }

    public AgentDagRunReply run(AgentDagRunRequest request) {
        return run(request, AgentTaskProgressSink.noop());
    }

    public AgentDagRunReply run(AgentDagRunRequest request, AgentTaskProgressSink progress) {
        String goal = request.goal() == null ? "" : request.goal().trim();
        List<AgentDagTask> tasks = validateAndNormalize(request.tasks());
        List<AgentDagAttempt> attempts = new ArrayList<>();
        Execution execution = execute(goal, tasks, 1, progress);
        AgentDagAttempt attempt = scoreAttempt(goal, execution, 1);
        emitCritique(attempt, progress);
        attempts.add(attempt);

        AgentDagProperties.Replan replan = properties.getReplan();
        int n = 1;
        while (replan != null
                && replan.isEnabled()
                && attempt.aggregate() < replan.getThreshold()
                && n <= Math.max(0, replan.getMaxReplans())) {
            progress.emit("dag-replan", Map.of(
                    "fromAttempt", n,
                    "threshold", replan.getThreshold(),
                    "aggregate", attempt.aggregate(),
                    "mainIssue", attempt.critique() == null ? "" : attempt.critique().mainIssue()));
            n++;
            tasks = replan(goal, tasks, attempt);
            progress.emit("dag-replanned", Map.of("attempt", n, "tasks", tasks));
            execution = execute(goal, tasks, n, progress);
            attempt = scoreAttempt(goal, execution, n);
            emitCritique(attempt, progress);
            attempts.add(attempt);
        }

        boolean accepted = replan == null || !replan.isEnabled() || attempt.aggregate() >= replan.getThreshold();
        return new AgentDagRunReply(
                goal,
                execution.levels(),
                execution.results(),
                execution.synthesis(),
                TenantContext.current().tenantId(),
                attempts,
                accepted);
    }

    private Execution execute(String goal, List<AgentDagTask> tasks, int attempt, AgentTaskProgressSink progress) {
        List<List<AgentDagTask>> levels = topologicalLevels(tasks);
        if (levels == null) {
            throw new IllegalArgumentException("task graph contains a cycle");
        }
        List<List<String>> levelIds = levels.stream()
                .map(level -> level.stream().map(AgentDagTask::id).toList())
                .toList();
        progress.emit("dag-levels", Map.of("attempt", attempt, "levels", levelIds));

        ConcurrentMap<String, AgentDagTaskResult> byId = new ConcurrentHashMap<>();
        List<AgentDagTaskResult> ordered = new ArrayList<>(tasks.size());
        for (int i = 0; i < levels.size(); i++) {
            List<AgentDagTask> level = levels.get(i);
            int levelIndex = i + 1;
            progress.emit("dag-level-start", Map.of(
                    "attempt", attempt,
                    "level", levelIndex,
                    "taskIds", level.stream().map(AgentDagTask::id).toList()));
            List<CompletableFuture<AgentDagTaskResult>> futures = level.stream()
                    .map(task -> CompletableFuture.supplyAsync(() -> {
                        progress.emit("dag-worker-start", Map.of(
                                "attempt", attempt,
                                "level", levelIndex,
                                "taskId", task.id(),
                                "description", task.description(),
                                "dependsOn", task.dependsOn()));
                        return runOne(goal, task, byId);
                    }, executor))
                    .toList();
            for (CompletableFuture<AgentDagTaskResult> future : futures) {
                AgentDagTaskResult result = future.join();
                byId.put(result.taskId(), result);
                ordered.add(result);
                progress.emit("dag-worker-result", Map.of(
                        "attempt", attempt,
                        "level", levelIndex,
                        "taskId", result.taskId(),
                        "description", result.description(),
                        "result", result.result()));
            }
            progress.emit("dag-level-complete", Map.of("attempt", attempt, "level", levelIndex));
        }

        progress.emit("dag-synthesis-start", Map.of("attempt", attempt));
        AgentRunReply synthesis = AgentRunMapper.toReply(agent.run(synthesisGoal(goal, ordered)));
        progress.emit("dag-synthesis-result", Map.of("attempt", attempt, "result", synthesis));
        return new Execution(levelIds, ordered, synthesis);
    }

    private AgentDagAttempt scoreAttempt(String goal, Execution execution, int n) {
        AgentDagProperties.Replan replan = properties.getReplan();
        if (replan == null || !replan.isEnabled()) {
            return new AgentDagAttempt(n, execution.levels(), execution.results(), execution.synthesis(), null, Double.NaN);
        }
        AgentDagCritique critique = critic.critique(goal, execution.synthesis().finalAnswer());
        double aggregate = aggregate(critique);
        return new AgentDagAttempt(n, execution.levels(), execution.results(), execution.synthesis(), critique, aggregate);
    }

    private void emitCritique(AgentDagAttempt attempt, AgentTaskProgressSink progress) {
        if (attempt.critique() == null) {
            return;
        }
        progress.emit("dag-critique", Map.of(
                "attempt", attempt.n(),
                "aggregate", attempt.aggregate(),
                "critique", attempt.critique()));
    }

    private List<AgentDagTask> replan(String goal, List<AgentDagTask> previousTasks, AgentDagAttempt previous) {
        AgentDagCritique critique = previous.critique();
        AgentDagPlan revised = replanner.revise(
                goal,
                previousPlanJson(previousTasks),
                previous.synthesis().finalAnswer(),
                critique.correctness(),
                critique.completeness(),
                critique.clarity(),
                critique.mainIssue());
        if (revised == null || revised.tasks().isEmpty()) {
            throw new IllegalArgumentException("replanner returned an empty plan");
        }
        List<AgentDagTask> tasks = revised.tasks().stream()
                .map(task -> new AgentDagTask(task.id(), task.description(), task.dependsOn()))
                .toList();
        return validateAndNormalize(tasks);
    }

    private double aggregate(AgentDagCritique critique) {
        AgentDagProperties.Weights weights = properties.getReplan().getWeights();
        double correctness = weights.getCorrectness();
        double completeness = weights.getCompleteness();
        double clarity = weights.getClarity();
        double sum = correctness + completeness + clarity;
        if (sum <= 0) {
            return (critique.correctness() + critique.completeness() + critique.clarity()) / 3.0;
        }
        return (correctness * critique.correctness()
                + completeness * critique.completeness()
                + clarity * critique.clarity()) / sum;
    }

    private String previousPlanJson(List<AgentDagTask> tasks) {
        try {
            return mapper.writeValueAsString(new AgentDagRunRequest("", tasks));
        } catch (JsonProcessingException ex) {
            log.warn("failed to serialize previous DAG plan, using toString: {}", ex.toString());
            return tasks.toString();
        }
    }

    List<List<AgentDagTask>> topologicalLevels(List<AgentDagTask> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        Map<String, AgentDagTask> byId = tasks.stream()
                .collect(Collectors.toMap(AgentDagTask::id, task -> task, (left, right) -> left, LinkedHashMap::new));
        Map<String, Set<String>> pendingDeps = new LinkedHashMap<>();
        for (AgentDagTask task : tasks) {
            Set<String> deps = new HashSet<>(task.dependsOn());
            deps.removeIf(dep -> {
                if (!byId.containsKey(dep)) {
                    log.warn("agent dag task {} depends on unknown id '{}', ignoring", task.id(), dep);
                    return true;
                }
                return false;
            });
            pendingDeps.put(task.id(), deps);
        }

        List<List<AgentDagTask>> levels = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        while (processed.size() < tasks.size()) {
            List<AgentDagTask> level = pendingDeps.entrySet().stream()
                    .filter(entry -> !processed.contains(entry.getKey()))
                    .filter(entry -> processed.containsAll(entry.getValue()))
                    .map(entry -> byId.get(entry.getKey()))
                    .toList();
            if (level.isEmpty()) {
                return null;
            }
            levels.add(Collections.unmodifiableList(level));
            level.forEach(task -> processed.add(task.id()));
        }
        return levels;
    }

    private AgentDagTaskResult runOne(String goal,
                                      AgentDagTask task,
                                      Map<String, AgentDagTaskResult> upstreamResults) {
        String workerGoal = workerGoal(goal, task, upstreamResults);
        AgentRunReply reply = AgentRunMapper.toReply(agent.run(workerGoal));
        return new AgentDagTaskResult(task.id(), task.description(), task.dependsOn(), reply);
    }

    private String workerGoal(String goal, AgentDagTask task, Map<String, AgentDagTaskResult> upstreamResults) {
        return """
                You are one worker in a multi-agent DAG.
                Execute only your assigned sub-task. Return a concise, self-contained result.

                Original user goal:
                %s

                Upstream results:
                %s

                Your sub-task [%s]:
                %s
                """.formatted(goal, upstreamText(task.dependsOn(), upstreamResults), task.id(), task.description());
    }

    private String synthesisGoal(String goal, List<AgentDagTaskResult> results) {
        String formatted = results.stream()
                .map(result -> "[%s] %s\n%s".formatted(
                        result.taskId(),
                        result.description(),
                        result.result().finalAnswer()))
                .collect(Collectors.joining("\n\n"));
        return """
                Synthesize the final answer for the original user goal using the completed DAG task results.
                Preserve useful nuance, remove duplication, and answer directly.

                Original user goal:
                %s

                Task results:
                %s
                """.formatted(goal, formatted);
    }

    private String upstreamText(List<String> depIds, Map<String, AgentDagTaskResult> upstreamResults) {
        if (depIds.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (String depId : depIds) {
            AgentDagTaskResult result = upstreamResults.get(depId);
            if (result == null) {
                continue;
            }
            sb.append("[").append(depId).append("] ").append(result.description()).append('\n');
            sb.append(result.result().finalAnswer()).append("\n\n");
        }
        return sb.isEmpty() ? "(none)" : sb.toString().trim();
    }

    private List<AgentDagTask> validateAndNormalize(List<AgentDagTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("tasks are required");
        }
        int maxTasks = Math.max(1, properties.getMaxTasks());
        if (tasks.size() > maxTasks) {
            throw new IllegalArgumentException("too many tasks; max is " + maxTasks);
        }
        Set<String> ids = new HashSet<>();
        List<AgentDagTask> normalized = new ArrayList<>(tasks.size());
        for (AgentDagTask task : tasks) {
            String id = task.id() == null ? "" : task.id().trim();
            String description = task.description() == null ? "" : task.description().trim();
            if (id.isBlank()) {
                throw new IllegalArgumentException("task id is required");
            }
            if (description.isBlank()) {
                throw new IllegalArgumentException("task description is required");
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate task id: " + id);
            }
            normalized.add(new AgentDagTask(id, description, task.dependsOn()));
        }
        return normalized;
    }

    private record Execution(List<List<String>> levels,
                             List<AgentDagTaskResult> results,
                             AgentRunReply synthesis) {
    }
}
