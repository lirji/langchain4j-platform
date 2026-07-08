package com.lrj.platform.conversation;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

/**
 * 流式对话 AiService（对齐单体 {@code /chat/stream}）。返回 {@link TokenStream}，由 langchain4j spring starter
 * 注入网关 {@code StreamingChatModel}（{@code platform-gateway-client} 新增）+ 同一 {@code ChatMemoryProvider}，
 * 因此流式链路同样具备按会话隔离的多轮记忆，完成时助手回复自动落进记忆。
 *
 * <p>与同步 {@link Assistant} 共用系统提示与 {@code @V("context")} RAG 注入约定；流式路径不挂语义缓存（缓存返回整段，
 * 无法 token 级重放），对齐单体流式不走缓存的行为。
 */
@AiService
public interface StreamingAssistant {

    @SystemMessage(Assistant.SYSTEM_PROMPT)
    TokenStream chat(@MemoryId String chatId,
                     @V("language") String language,
                     @V("tone") String tone,
                     @V("citationPolicy") String citationPolicy,
                     @V("extra") String extra,
                     @UserMessage String message,
                     @V("context") String context);
}
