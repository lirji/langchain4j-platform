package com.lrj.platform.workflow;

import com.lrj.platform.protocol.conversation.TicketDraftRequest;
import com.lrj.platform.protocol.conversation.TicketDraftResponse;
import com.lrj.platform.protocol.conversation.WorkflowReplyRequest;
import com.lrj.platform.protocol.conversation.WorkflowReplyResponse;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/** AgentScope 候选 AI 适配器；只生成草稿，不接触 Flowable、审批 API 或数据库。 */
public class AgentScopeWorkflowAiClient implements WorkflowAiClient {

    private final RestTemplate restTemplate;

    public AgentScopeWorkflowAiClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public Ticket extractTicket(String message) {
        TicketDraftResponse response = restTemplate.postForObject(
                "/internal/workflow/ticket-draft",
                new TicketDraftRequest(message),
                TicketDraftResponse.class);
        if (response == null) {
            throw new IllegalStateException("AgentScope 工单草稿返回空响应体");
        }
        return new Ticket(
                response.title(),
                HttpWorkflowAiClient.mapPriority(response.priority()),
                response.category(),
                response.summary(),
                response.tags() == null ? List.of() : response.tags());
    }

    @Override
    public String resolveReply(String scopedChatId, String message) {
        WorkflowReplyResponse response = restTemplate.postForObject(
                "/internal/workflow/reply-draft",
                new WorkflowReplyRequest(scopedChatId, message),
                WorkflowReplyResponse.class);
        if (response == null || response.reply() == null || response.reply().isBlank()) {
            throw new IllegalStateException("AgentScope 答复草稿返回空响应体");
        }
        return response.reply();
    }
}
