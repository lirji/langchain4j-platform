package com.lrj.platform.agent.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;

// 与 HttpWorkflowClient 互补：Http 在 agent.enabled && workflow.enabled 时注册，
// 本兜底覆盖 agent.enabled && !workflow.enabled 的缺口。workflow 默认关时三个 workflow 动作也不注册，
// 严格说无消费者——保留 Noop 是与 analytics 三件套一致的防御写法，防止将来出现「常在」消费者时启动失败。
// 不用 @ConditionalOnMissingBean：其在组件扫描下对注册顺序敏感、不可靠（见 NoopAnalyticsClient）。
@Component
@ConditionalOnExpression("${app.agent.enabled:true} and !${app.agent.workflow.enabled:false}")
public class NoopWorkflowClient implements WorkflowClient {

    private static final String DISABLED = "workflow action disabled";

    @Override
    public StartOutcome startRefund(String message) {
        return new StartOutcome(null, DISABLED);
    }

    @Override
    public InstanceOutcome instance(String instanceId) {
        return new InstanceOutcome(null, DISABLED);
    }

    @Override
    public TasksOutcome tasks() {
        return new TasksOutcome(List.of(), DISABLED);
    }
}
