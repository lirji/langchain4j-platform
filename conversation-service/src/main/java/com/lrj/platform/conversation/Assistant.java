package com.lrj.platform.conversation;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.spring.AiService;

/**
 * Conversation 上下文的对话 AiService。LangChain4j spring starter 自动装配注入网关 ChatModel
 * （{@code platform-gateway-client} 提供的唯一 ChatModel Bean）。
 *
 * <p>RAG 由 {@link RagPromptAugmenter} 在调用 AiService 前通过跨服务 knowledge 协议注入。
 */
@AiService
public interface Assistant {

    @SystemMessage("""
            你是企业 AI 平台的对话助手。用简洁中文回答，1–2 句话答完，必要时再展开。
            不知道就直说不知道，不要编造。
            """)
    String chat(String message);
}
