package com.lrj.platform.conversation;

import com.lrj.platform.protocol.conversation.TicketDraftRequest;
import com.lrj.platform.protocol.conversation.TicketDraftResponse;
import com.lrj.platform.protocol.conversation.WorkflowReplyRequest;
import com.lrj.platform.protocol.conversation.WorkflowReplyResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * workflow → conversation 的跨服务能力端点（C2 / B9）。把「答复生成」「结构化工单抽取」暴露给
 * workflow-service，使其断开对本地 ChatModel 的依赖。租户身份仍由内部 JWT 经
 * {@code InternalTokenAuthFilter} 还原进 {@code TenantContext}，与 {@code /chat} 一致。
 *
 * <p>LLM 藏在 {@link WorkflowAssistant} 接口后，本 controller 可纯 POJO 单测。
 */
@RestController
public class WorkflowSupportController {

    private final WorkflowAssistant assistant;

    public WorkflowSupportController(WorkflowAssistant assistant) {
        this.assistant = assistant;
    }

    /** 生成给用户的受理/通过答复。 */
    @PostMapping("/conversation/workflow/reply")
    public WorkflowReplyResponse reply(@RequestBody WorkflowReplyRequest request) {
        String message = request == null || request.message() == null ? "" : request.message();
        return new WorkflowReplyResponse(assistant.resolveReply(message));
    }

    /** 结构化抽取退款工单。 */
    @PostMapping("/conversation/workflow/ticket")
    public TicketDraftResponse ticket(@RequestBody TicketDraftRequest request) {
        String message = request == null || request.message() == null ? "" : request.message();
        TicketDraft draft = assistant.extractTicket(message);
        String priority = draft.priority() == null ? null : draft.priority().name();
        List<String> tags = draft.tags() == null ? List.of() : draft.tags();
        return new TicketDraftResponse(draft.title(), priority, draft.category(), draft.summary(), tags);
    }
}
