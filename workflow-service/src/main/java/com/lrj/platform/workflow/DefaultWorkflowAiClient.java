package com.lrj.platform.workflow;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class DefaultWorkflowAiClient implements WorkflowAiClient {

    private final ObjectProvider<ChatModel> chatModelProvider;

    public DefaultWorkflowAiClient(ObjectProvider<ChatModel> chatModelProvider) {
        this.chatModelProvider = chatModelProvider;
    }

    @Override
    public Ticket extractTicket(String message) {
        String text = message == null ? "" : message;
        String lower = text.toLowerCase(Locale.ROOT);
        Ticket.Priority priority = (lower.contains("投诉") || lower.contains("急") || lower.contains("严重")
                || lower.contains("一直") || lower.contains("不到账"))
                ? Ticket.Priority.HIGH : Ticket.Priority.LOW;
        String summary = text.isBlank() ? "退款请求" : text.strip();
        return new Ticket("退款请求", priority, "refund", summary, List.of());
    }

    @Override
    public String resolveReply(String scopedChatId, String message) {
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            return fallbackReply();
        }
        return model.chat("""
                你是客服助手。用户提交的退款相关请求现已可以处理。
                请用中文写一段简洁、礼貌的确认答复，告知用户退款将被处理。
                用户原始请求：%s
                """.formatted(message == null ? "" : message));
    }

    static String fallbackReply() {
        return "您的退款请求已受理，我们会尽快处理并同步进展。";
    }
}
