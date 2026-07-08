package com.lrj.platform.conversation.extract;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * 结构化抽取目标 POJO（迁移单体 {@code ai/extract/Ticket}）。每个 {@link Description} 会被 langchain4j
 * 序列化进发给模型的 JSON Schema，约束字段类型；系统提示 + few-shot 负责判断口径（优先级边界/文风）。
 */
public record Ticket(
        @Description("Short title summarizing the issue, under 80 chars") String title,
        @Description("One of: CRITICAL, HIGH, MEDIUM, LOW") Priority priority,
        @Description("Free-text category/topic, e.g. billing, auth, performance") String category,
        @Description("Concise customer-facing summary of the problem (1-2 sentences)") String summary,
        @Description("Up to 5 actionable next steps an engineer should take") List<String> nextSteps
) {
    public enum Priority {CRITICAL, HIGH, MEDIUM, LOW}
}
