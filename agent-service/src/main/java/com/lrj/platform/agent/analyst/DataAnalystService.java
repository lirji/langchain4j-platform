package com.lrj.platform.agent.analyst;

import com.lrj.platform.agent.async.AgentTaskProgressSink;
import com.lrj.platform.agent.dag.AgentDagPlan;
import com.lrj.platform.agent.dag.AgentDagService;
import com.lrj.platform.protocol.agent.AgentDagRunReply;
import com.lrj.platform.protocol.agent.AgentDagRunRequest;
import com.lrj.platform.protocol.agent.AgentDagTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 数据分析智能体：用数据分析专用 {@link DataAnalystPlanner} 把问题拆成「探表→取数→计算→解读」子任务，
 * 再交给现有 {@link AgentDagService} 引擎执行（并行/依赖/synthesis 收口/可选 critic+replan）。
 *
 * <p>刻意<strong>复用引擎、不改 {@code AgentDagService}</strong>：这里只负责「用专用 planner 规划」，
 * 规划后调 public 的 {@code dag.run(request, progress)} 复用其全部执行逻辑。
 */
@Service
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class DataAnalystService {

    private final DataAnalystPlanner planner;
    private final AgentDagService dag;

    public DataAnalystService(DataAnalystPlanner planner, AgentDagService dag) {
        this.planner = planner;
        this.dag = dag;
    }

    public AgentDagRunReply analyze(String goal) {
        return analyze(goal, AgentTaskProgressSink.noop());
    }

    public AgentDagRunReply analyze(String goal, AgentTaskProgressSink progress) {
        AgentDagPlan plan = planner.plan(goal);
        List<AgentDagTask> tasks = plan == null || plan.tasks().isEmpty()
                ? List.of(new AgentDagTask("t1", goal, List.of()))
                : plan.tasks().stream()
                        .map(task -> new AgentDagTask(task.id(), task.description(), task.dependsOn()))
                        .toList();
        progress.emit("dag-planned", Map.of("goal", goal, "tasks", tasks));
        return dag.run(new AgentDagRunRequest(goal, tasks), progress);
    }
}
