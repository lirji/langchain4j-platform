package com.lrj.platform.workflow;

/** Workflow service 对 AI 能力的本地端口。后续可替换为 conversation-service HTTP client。 */
public interface WorkflowAiClient {

    Ticket extractTicket(String message);

    String resolveReply(String scopedChatId, String message);
}
