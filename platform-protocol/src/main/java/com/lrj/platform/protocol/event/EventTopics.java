package com.lrj.platform.protocol.event;

/**
 * 事件总线 topic 名集中常量。每个业务域一个主 topic + 对应死信（.DLT）。
 * 主 topic 按 tenantId 分区（key=tenantId），保证同租户有序。
 */
public final class EventTopics {

    private EventTopics() {
    }

    /** 死信 topic 后缀（DeadLetterPublishingRecoverer 默认约定）。 */
    public static final String DLT_SUFFIX = ".DLT";

    /** 工作流终态事件。 */
    public static final String WORKFLOW_TERMINAL = "platform.workflow.terminal";
    public static final String WORKFLOW_TERMINAL_DLT = WORKFLOW_TERMINAL + DLT_SUFFIX;

    /** 异步任务生命周期事件。 */
    public static final String ASYNCTASK_LIFECYCLE = "platform.asynctask.lifecycle";
    public static final String ASYNCTASK_LIFECYCLE_DLT = ASYNCTASK_LIFECYCLE + DLT_SUFFIX;

    /** 审计事件。 */
    public static final String AUDIT_EVENTS = "platform.audit.events";
    public static final String AUDIT_EVENTS_DLT = AUDIT_EVENTS + DLT_SUFFIX;

    /** 用量/计费事件。 */
    public static final String METERING_USAGE = "platform.metering.usage";
    public static final String METERING_USAGE_DLT = METERING_USAGE + DLT_SUFFIX;
}
