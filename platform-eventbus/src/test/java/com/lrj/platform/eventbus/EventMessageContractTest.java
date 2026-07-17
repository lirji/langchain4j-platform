package com.lrj.platform.eventbus;

import com.lrj.platform.protocol.event.AuditEventMessage;
import com.lrj.platform.protocol.event.EventTopics;
import com.lrj.platform.protocol.event.UsageEventMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EventMessageContractTest：验证 {@code platform-protocol} 事件契约 record 的不可变性与默认值——
 * {@link com.lrj.platform.protocol.event.AuditEventMessage} 对 fields 做防御性拷贝并返回只读视图（null → 空 map），
 * {@link com.lrj.platform.protocol.event.UsageEventMessage} 的 occurredAt 为 null 时补默认时间，
 * 以及 {@link com.lrj.platform.protocol.event.EventTopics} 死信主题遵循 {@code .DLT} 后缀约定。
 */
class EventMessageContractTest {

    @Test
    void auditEventDefensivelyCopiesFieldsSoRecordIsImmutable() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v");
        AuditEventMessage msg = new AuditEventMessage(
                "evt-1", "acme", "alice", "chat", "trace-1", Instant.now(), mutable);

        // 外部后续改动不得影响已构造事件
        mutable.put("k2", "v2");
        assertThat(msg.fields()).containsExactly(Map.entry("k", "v"));

        // 返回的视图不可变
        assertThatThrownBy(() -> msg.fields().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void auditEventNullFieldsBecomeEmptyMap() {
        AuditEventMessage msg = new AuditEventMessage(
                "evt-1", "acme", "alice", "chat", "trace-1", Instant.now(), null);
        assertThat(msg.fields()).isEmpty();
    }

    @Test
    void occurredAtDefaultsWhenNull() {
        UsageEventMessage msg = new UsageEventMessage(
                "evt-1", "acme", "gpt-4o", "openai", 10, 20, 0.5, null, "trace-1");
        assertThat(msg.occurredAt()).isNotNull();
    }

    @Test
    void dltTopicsUseConventionalSuffix() {
        assertThat(EventTopics.WORKFLOW_TERMINAL_DLT).isEqualTo("platform.workflow.terminal.DLT");
        assertThat(EventTopics.ASYNCTASK_LIFECYCLE_DLT).isEqualTo("platform.asynctask.lifecycle.DLT");
        assertThat(EventTopics.AUDIT_EVENTS_DLT).isEqualTo("platform.audit.events.DLT");
        assertThat(EventTopics.METERING_USAGE_DLT).isEqualTo("platform.metering.usage.DLT");
    }
}
