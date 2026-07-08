package com.lrj.platform.conversation.extract;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 通用结构化抽取（迁移单体 {@code ai/extract/Extractor}）：自由文本 → 类型化 POJO，靠 langchain4j structured output。
 * 由 {@link ExtractorConfig} 经 {@code AiServices.builder} 程序化构建（无记忆、无检索，一次性抽取）。
 */
public interface Extractor {

    @SystemMessage("""
            You convert a raw support report into a structured Ticket.

            # Priority rubric (be strict — most tickets are MEDIUM)
            - CRITICAL: production outage, data loss, security breach, or
              core functionality blocked for many/all users; regulatory deadline.
            - HIGH: significant degradation, key feature broken, OR a specific
              paying customer with a near-term deadline.
            - MEDIUM: real issue with a workaround, or affects a subset of
              users; should be fixed within the current sprint.
            - LOW: cosmetic, single user, "nice to have", or no functional impact.

            # Style rules
            - title: factual, under 80 chars, no marketing fluff or vague verbs
            - summary: 1–2 sentences a support agent could read aloud
            - nextSteps: concrete and ordered. Say WHAT to do, not "investigate"
              or "look into". 2–5 items.
            - Match the language of the input for title / summary / nextSteps
              (Chinese input → Chinese output).
            """)
    @UserMessage("Extract a Ticket from this report:\n{{it}}")
    Ticket extractTicket(String text);
}
