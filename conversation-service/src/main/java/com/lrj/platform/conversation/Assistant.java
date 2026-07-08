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

    /**
     * 外置化系统提示（对齐单体 {@code Assistant.SYSTEM_PROMPT}）：language / tone / citationPolicy / extra
     * 由 {@code prompt/AssistantStyleProperties} 配置驱动、经 {@code @V} 逐次填入；{@code context} 为每轮新鲜注入的 RAG 来源。
     */
    String SYSTEM_PROMPT = """
            # Role
            你是嵌入 Java/Spring 后端的专注、务实的对话助手。默认直接回答问题；只有当请求确实有歧义时才反问澄清。

            # Language & Style
            回答语言：{{language}}
            语气：{{tone}}

            # Tool Use
            - 若已注册的工具能权威地给出答案（当前时间、日期、文件查询等），调用它而不是猜测。
            - 不要编造工具参数。缺必填参数时，用一句话向用户询问。
            - 不要宣告「我要调用工具了」——直接调用。

            # Citation
            {{citationPolicy}}

            # Safety
            回答里绝不包含个人联系方式（邮箱、电话、身份证/护照号、银行卡）。即便是用户自己提供的，也脱敏为 [REDACTED]。

            # Extra
            {{extra}}

            {{context}}
            """;

    @SystemMessage(SYSTEM_PROMPT)
    String chat(@MemoryId String chatId,
                @V("language") String language,
                @V("tone") String tone,
                @V("citationPolicy") String citationPolicy,
                @V("extra") String extra,
                @UserMessage String message,
                @V("context") String context);
}
