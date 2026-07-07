package com.lrj.platform.workflow;

import com.lrj.platform.protocol.conversation.TicketDraftRequest;
import com.lrj.platform.protocol.conversation.TicketDraftResponse;
import com.lrj.platform.protocol.conversation.WorkflowReplyRequest;
import com.lrj.platform.protocol.conversation.WorkflowReplyResponse;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Locale;

/**
 * {@link WorkflowAiClient} 的 HTTP 实现（C2 / B9）：经带 tenant/trace 传播的 RestTemplate 调
 * conversation-service 的 {@code /conversation/workflow/**} 端点，取代 {@link DefaultWorkflowAiClient}
 * 直连本地 ChatModel 的兜底。默认实现（{@code app.workflow.ai-client.mode=http}）。
 *
 * <p><b>铁律（B9 风险）</b>：本客户端在任何失败（网络异常 / 非 2xx / 空响应体）时都<b>抛异常</b>，
 * <em>绝不</em>静默吞掉返回空/兜底值——否则抽取失败会被误判成 LOW、把高风险退款自动放过。
 * 抛出的异常由 {@code ServiceTaskDelegates} 的 {@code withRetry} 捕获：有界重试后仍失败则走
 * 那里的降级兜底（assess → HIGH 转人工；resolve → 已受理+转人工话术），事务照常提交、不回滚人工审批决定。
 *
 * <p>{@code RestTemplate} 由 {@link WorkflowConfig#conversationRestTemplate} 构建，设紧 connect/read
 * 超时（ServiceTask 在 Flowable 同步事务内调用，不能长时间占用事务）。
 */
public class HttpWorkflowAiClient implements WorkflowAiClient {

    private final RestTemplate restTemplate;

    public HttpWorkflowAiClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public Ticket extractTicket(String message) {
        // RestClientException 直接向上抛（不 catch），交给 ServiceTaskDelegates.withRetry。
        TicketDraftResponse r = restTemplate.postForObject(
                "/conversation/workflow/ticket", new TicketDraftRequest(message), TicketDraftResponse.class);
        if (r == null) {
            throw new IllegalStateException("conversation 工单抽取返回空响应体");
        }
        List<String> tags = r.tags() == null ? List.of() : r.tags();
        return new Ticket(r.title(), mapPriority(r.priority()), r.category(), r.summary(), tags);
    }

    @Override
    public String resolveReply(String scopedChatId, String message) {
        WorkflowReplyResponse r = restTemplate.postForObject(
                "/conversation/workflow/reply", new WorkflowReplyRequest(scopedChatId, message), WorkflowReplyResponse.class);
        if (r == null || r.reply() == null || r.reply().isBlank()) {
            throw new IllegalStateException("conversation 答复生成返回空响应体");
        }
        return r.reply();
    }

    /** 优先级字符串 → 内部枚举。无法识别（null/未知）时从严取 HIGH（转人工），绝不默认 LOW 放过高风险退款。 */
    static Ticket.Priority mapPriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return Ticket.Priority.HIGH;
        }
        try {
            return Ticket.Priority.valueOf(priority.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Ticket.Priority.HIGH;
        }
    }
}
