package com.lrj.platform.workflow;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * BPMN {@code end} 事件的 ExecutionListener（B1b 收口）。由 {@code refund-approval.bpmn20.xml} 的
 * {@code <flowable:executionListener event="end" delegateExpression="${workflowTerminalOutboxListener}"/>} 触发。
 *
 * <p><b>为什么在这里写 outbox</b>：本监听器随流程到达 end 事件、在 Flowable 引擎命令的<b>同一事务</b>内同步执行
 * （async executor 关，见 {@link WorkflowConfig}）。此刻 {@code ACT_*} 终态、{@code WF_REPLY}（resolve/reject
 * 服务任务已写）都还在同一个未提交事务里。{@link WorkflowTerminalEventOutbox} 用同一 {@code workflowDataSource}
 * 的连接 INSERT → 事件 outbox 行与终态<b>原子提交</b>。相较旧实现「{@code taskService.complete()} 返回（事务已提交）
 * 之后再发布」，彻底消除了两段之间崩溃导致的终态通知丢失窗口。
 *
 * <p><b>始终装配、按 mode 决定是否落库</b>：BPMN 的 {@code delegateExpression} 不感知模式，故本 bean 在
 * {@code app.workflow.enabled=true} 时必须存在（否则流程到 end 会因解析不到 bean 失败）；仅当
 * {@code app.workflow.terminal-notification.mode=kafka} 时才写事件 outbox，其余模式（local/async-task）直接 no-op，
 * 保持既有行为零影响。
 */
@Component("workflowTerminalOutboxListener")
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class WorkflowTerminalOutboxListener implements ExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTerminalOutboxListener.class);

    private final WorkflowTerminalEventOutbox eventOutbox;
    private final WorkflowProperties props;

    public WorkflowTerminalOutboxListener(WorkflowTerminalEventOutbox eventOutbox, WorkflowProperties props) {
        this.eventOutbox = eventOutbox;
        this.props = props;
    }

    @Override
    public void notify(DelegateExecution execution) {
        if (!WorkflowService.isKafkaMode(props.getTerminalNotification().getMode())) {
            return; // 非 kafka 档：终态通知走 local/async-task 原路径，这里不落事件 outbox
        }
        String instanceId = execution.getProcessInstanceId();
        String tenantId = str(execution.getVariable("tenantId"));
        String chatId = str(execution.getVariable("chatId"));
        String outcome = str(execution.getVariable("terminalOutcome"));
        String webhookUrl = str(execution.getVariable("webhookUrl"));
        eventOutbox.enqueue(instanceId, tenantId, chatId, outcome, webhookUrl, System.currentTimeMillis());
        log.debug("workflow terminal event outbox 已入队（Flowable 事务内）instanceId={} outcome={}", instanceId, outcome);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
