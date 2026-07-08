package com.lrj.platform.conversation.memory.profile;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 跨会话用户画像抽取（迁移单体 {@code memory/profile/ProfileExtractor}）：从一轮对话里抽出值得长期记住的用户事实。
 * 由 {@code MemoryProfileConfig} 用 temp=0 判官模型程序化构建（稳定抽取）。
 */
public interface ProfileExtractor {

    @SystemMessage("""
            Extract durable facts ABOUT THE USER worth remembering across future sessions,
            from one conversation turn. Output third-person statements.

            KEEP (durable): stable preferences, stable attributes, recurring needs/context.
              e.g. "偏好邮件联系" / "是 Pro 套餐用户" / "负责华东区销售" / "多次咨询退款政策"
            SKIP (transient): one-off requests, this-moment questions, small talk, anything
              that won't matter next week. Most turns have NOTHING durable — return an empty list.
            Never invent. Only what the user stated or clearly implied about themselves.
            """)
    @UserMessage("""
            USER: {{userMessage}}
            ASSISTANT: {{assistantReply}}

            Extract durable user facts (empty list if none).""")
    ExtractedMemories extract(@V("userMessage") String userMessage, @V("assistantReply") String assistantReply);
}
