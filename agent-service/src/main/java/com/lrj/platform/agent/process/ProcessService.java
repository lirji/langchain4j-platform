package com.lrj.platform.agent.process;

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
 * 业务流程智能体：用 {@link ProcessPlanner} 把流程诉求拆成子任务，再交给现有 {@link AgentDagService} 引擎执行。
 * 与数据分析智能体同款：复用引擎、不改 AgentDagService；智能体只在流程外编排（发起/查询/汇报），
 * 不进 Flowable 同步 ServiceTask。双门控默认关（有副作用）。
 */
@Service
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.workflow.enabled"}, havingValue = "true")
public class ProcessService {

    private final ProcessPlanner planner;
    private final AgentDagService dag;

    public ProcessService(ProcessPlanner planner, AgentDagService dag) {
        this.planner = planner;
        this.dag = dag;
    }

    public AgentDagRunReply run(String goal) {
        return run(goal, AgentTaskProgressSink.noop());
    }

    public AgentDagRunReply run(String goal, AgentTaskProgressSink progress) {
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
