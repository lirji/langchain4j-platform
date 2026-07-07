package com.lrj.platform.conversation.cascade;

import com.lrj.platform.gateway.cascade.CascadeChatModel;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Model Cascade 服务入口：把一句自然语言问题跑一遍级联，返回答案 + 由谁作答。
 *
 * <p>薄封装 {@link CascadeChatModel#escalate}，把 served 明细透传给 {@link CascadeController}。
 * <strong>{@link CascadeChatModel} 作为本 Bean 的私有字段持有，不进 Spring 容器</strong>（避开
 * langchain4j {@code @AiService} 见到 &gt;1 个 ChatModel Bean 抛 conflict）。不带 ChatMemory / RAG——
 * 级联是「模型选择」层，与检索 / 记忆正交。
 */
public class CascadeService {

    private final CascadeChatModel cascade;

    public CascadeService(CascadeChatModel cascade) {
        this.cascade = cascade;
    }

    public CascadeResult ask(String question) {
        String q = question == null ? "" : question;
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(q))
                .build();
        CascadeChatModel.Outcome outcome = cascade.escalate(request);
        ChatResponse resp = outcome.response();
        String answer = (resp.aiMessage() == null) ? "" : resp.aiMessage().text();
        return new CascadeResult(q, answer, outcome.served(), outcome.cheapConfident(),
                TenantContext.current().tenantId());
    }
}
