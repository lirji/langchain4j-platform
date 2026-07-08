package com.lrj.platform.conversation;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

/**
 * Conversation 上下文的对话 AiService。LangChain4j spring starter 自动装配注入网关 ChatModel
 * （{@code platform-gateway-client} 提供的唯一 ChatModel Bean）与 {@code ChatMemoryProvider}
 * （{@code memory/ChatMemoryConfig}）—— 后者按 {@link MemoryId} 提供按会话隔离的多轮记忆。
 *
 * <p>RAG 由 {@link RagPromptAugmenter} 在调用前算好「知识来源」上下文，经 {@link V}{@code ("context")}
 * 注入系统提示；用户原始消息经 {@link UserMessage} 进入记忆，检索到的来源不落进历史（每轮新鲜注入），
 * 避免多轮下 source 片段在记忆里累积膨胀。
 */
@AiService
public interface Assistant {

    @SystemMessage("""
            你是企业 AI 平台的对话助手。用简洁中文回答，1–2 句话答完，必要时再展开。
            不知道就直说不知道，不要编造。
            {{context}}
            """)
    String chat(@MemoryId String chatId,
                @UserMessage String message,
                @V("context") String context);
}
