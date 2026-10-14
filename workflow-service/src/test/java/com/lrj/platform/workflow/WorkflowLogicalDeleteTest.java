package com.lrj.platform.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** 删除后的延迟回调不能重新唤起消息；同时覆盖领取SQL的OR分支。 */
class WorkflowLogicalDeleteTest {
    @Test
    void tombstonesSurviveLateCallbacksAndRemainInvisibleToDispatchers() {
        var source = WorkflowTestDatabase.migrated("workflow_soft_delete");
        var jdbc = new JdbcTemplate(source);
        var replies = new JdbcWorkflowReplyStore(source);
        var http = new WorkflowOutbox(source);
        var events = new WorkflowTerminalEventOutbox(source);
        replies.save("soft-reply", null, true);
        assertThat(replies.find("soft-reply")).isNull();
        replies.save("soft-reply", "original", false);
        replies.delete("soft-reply");
        replies.save("soft-reply", "late", false);
        assertThat(replies.find("soft-reply")).isNull();
        assertThat(jdbc.queryForObject("SELECT REPLY FROM WF_REPLY WHERE INSTANCE_ID='soft-reply'", String.class)).isEqualTo("original");
        http.enqueue("soft-http", "tenant", "http://callback.local/original", 1000);
        http.claimDue(1000, 10, "old-worker", 1000);
        http.delete("soft-http");
        http.enqueue("soft-http", "tenant", "http://callback.local/late", 3000);
        assertThat(http.claimDue(5000, 10)).isEmpty();
        assertThat(http.markDelivered("soft-http", "old-worker", 1500)).isFalse();
        events.enqueue("soft-event", "tenant", "chat", "done", null, 1000);
        events.delete("soft-event");
        events.enqueue("soft-event", "tenant", "chat", "late", null, 3000);
        assertThat(events.claimDue(5000, 10)).isEmpty();
        for (String table : java.util.List.of("WF_REPLY", "WF_OUTBOX", "WF_TERMINAL_EVENT_OUTBOX")) {
            // 表名来自固定测试允许列表；断言墓碑实际存在，不能只证明查询无结果。
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE DELETED=1", Integer.class)).isEqualTo(1);
        }
    }
}
