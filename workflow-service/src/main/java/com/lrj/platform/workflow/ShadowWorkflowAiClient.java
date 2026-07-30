package com.lrj.platform.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 返回 primary 结果，并 best-effort 双跑 AgentScope 候选。候选结果绝不参与路由、审批或最终答复。
 */
public class ShadowWorkflowAiClient implements WorkflowAiClient {

    private static final Logger log = LoggerFactory.getLogger(ShadowWorkflowAiClient.class);
    private final WorkflowAiClient primary;
    private final WorkflowAiClient candidate;

    public ShadowWorkflowAiClient(WorkflowAiClient primary, WorkflowAiClient candidate) {
        this.primary = primary;
        this.candidate = candidate;
    }

    @Override
    public Ticket extractTicket(String message) {
        Ticket result = primary.extractTicket(message);
        try {
            candidate.extractTicket(message);
        } catch (RuntimeException exception) {
            log.warn("workflow ticket candidate shadow failed: {}", exception.getClass().getSimpleName());
        }
        return result;
    }

    @Override
    public String resolveReply(String scopedChatId, String message) {
        String result = primary.resolveReply(scopedChatId, message);
        try {
            candidate.resolveReply(scopedChatId, message);
        } catch (RuntimeException exception) {
            log.warn("workflow reply candidate shadow failed: {}", exception.getClass().getSimpleName());
        }
        return result;
    }
}
