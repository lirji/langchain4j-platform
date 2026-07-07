package com.lrj.platform.conversation;

import com.lrj.platform.protocol.conversation.TicketDraftRequest;
import com.lrj.platform.protocol.conversation.TicketDraftResponse;
import com.lrj.platform.protocol.conversation.WorkflowReplyRequest;
import com.lrj.platform.protocol.conversation.WorkflowReplyResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 纯 POJO 单测：workflow → conversation 端点把 {@link WorkflowAssistant}（LLM 藏其后）结果映射为 protocol DTO。
 * mock 掉 assistant，不起 Spring context、不连模型。
 */
class WorkflowSupportControllerTest {

    @Test
    void reply_mapsAssistantOutput() {
        WorkflowAssistant assistant = mock(WorkflowAssistant.class);
        when(assistant.resolveReply("退款没到账")).thenReturn("您的退款将尽快处理。");
        WorkflowSupportController controller = new WorkflowSupportController(assistant);

        WorkflowReplyResponse resp = controller.reply(new WorkflowReplyRequest("acme:c1", "退款没到账"));

        assertThat(resp.reply()).isEqualTo("您的退款将尽快处理。");
        verify(assistant).resolveReply("退款没到账");
    }

    @Test
    void reply_nullMessage_passesEmptyString() {
        WorkflowAssistant assistant = mock(WorkflowAssistant.class);
        when(assistant.resolveReply("")).thenReturn("ok");
        WorkflowSupportController controller = new WorkflowSupportController(assistant);

        WorkflowReplyResponse resp = controller.reply(new WorkflowReplyRequest("acme:c1", null));

        assertThat(resp.reply()).isEqualTo("ok");
        verify(assistant).resolveReply("");
    }

    @Test
    void ticket_mapsPriorityEnumToStringAndDefaultsTags() {
        WorkflowAssistant assistant = mock(WorkflowAssistant.class);
        when(assistant.extractTicket(any())).thenReturn(
                new TicketDraft("退款请求", TicketDraft.Priority.HIGH, "refund", "投诉：一直不到账", null));
        WorkflowSupportController controller = new WorkflowSupportController(assistant);

        TicketDraftResponse resp = controller.ticket(new TicketDraftRequest("投诉：一直不到账"));

        assertThat(resp.priority()).isEqualTo("HIGH");
        assertThat(resp.title()).isEqualTo("退款请求");
        assertThat(resp.category()).isEqualTo("refund");
        assertThat(resp.summary()).isEqualTo("投诉：一直不到账");
        assertThat(resp.tags()).isEqualTo(List.of());
    }
}
