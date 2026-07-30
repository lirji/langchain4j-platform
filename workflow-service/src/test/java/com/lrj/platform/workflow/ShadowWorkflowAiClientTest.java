package com.lrj.platform.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShadowWorkflowAiClientTest {

    @Test
    void candidateCannotChangePrimaryTicketDecision() {
        Ticket primaryTicket = new Ticket("primary", Ticket.Priority.HIGH, "refund", "manual", List.of());
        WorkflowAiClient primary = fixed(primaryTicket, "primary reply");
        WorkflowAiClient candidate = fixed(
                new Ticket("candidate", Ticket.Priority.LOW, "refund", "auto", List.of()),
                "candidate reply");

        ShadowWorkflowAiClient client = new ShadowWorkflowAiClient(primary, candidate);

        assertThat(client.extractTicket("request")).isSameAs(primaryTicket);
        assertThat(client.resolveReply("chat", "request")).isEqualTo("primary reply");
    }

    @Test
    void candidateFailureCannotRollbackPrimaryResult() {
        Ticket primaryTicket = new Ticket("primary", Ticket.Priority.HIGH, "refund", "manual", List.of());
        WorkflowAiClient failing = new WorkflowAiClient() {
            @Override public Ticket extractTicket(String message) { throw new IllegalStateException("down"); }
            @Override public String resolveReply(String scopedChatId, String message) { throw new IllegalStateException("down"); }
        };
        ShadowWorkflowAiClient client = new ShadowWorkflowAiClient(fixed(primaryTicket, "ok"), failing);

        assertThat(client.extractTicket("request")).isSameAs(primaryTicket);
        assertThat(client.resolveReply("chat", "request")).isEqualTo("ok");
    }

    private static WorkflowAiClient fixed(Ticket ticket, String reply) {
        return new WorkflowAiClient() {
            @Override public Ticket extractTicket(String message) { return ticket; }
            @Override public String resolveReply(String scopedChatId, String message) { return reply; }
        };
    }
}
