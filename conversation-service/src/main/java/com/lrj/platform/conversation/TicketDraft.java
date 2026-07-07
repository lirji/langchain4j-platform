package com.lrj.platform.conversation;

import java.util.List;

/**
 * conversation 侧「结构化工单抽取」的 LLM 结构化输出模型（C2 / B9：抽取端点归 conversation）。
 *
 * <p>作为 {@link WorkflowAssistant#extractTicket} 的返回类型，由 langchain4j 结构化输出直接填充。
 * {@link Priority} 用枚举以约束模型输出取值可靠；跨服务传输时由 {@code WorkflowSupportController}
 * 映射为 protocol 的字符串优先级。
 */
public record TicketDraft(String title, Priority priority, String category, String summary, List<String> tags) {

    public enum Priority {
        LOW, MEDIUM, HIGH, CRITICAL
    }
}
