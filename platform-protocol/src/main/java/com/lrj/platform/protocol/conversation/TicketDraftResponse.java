package com.lrj.platform.protocol.conversation;

import java.util.List;

/**
 * conversation → workflow 的「结构化工单抽取」响应契约（C2）。
 *
 * <p>{@code priority} 用 {@link String} 而非 workflow 侧的 {@code Ticket.Priority} 枚举，
 * 避免 protocol 反向耦合具体服务的内部模型。约定取值为 {@code LOW|MEDIUM|HIGH|CRITICAL}；
 * workflow 侧负责把它映射回内部枚举，<b>无法识别时保守取 HIGH（转人工），绝不默认 LOW 放过高风险退款</b>。
 *
 * @param title    工单标题
 * @param priority 优先级字符串：LOW / MEDIUM / HIGH / CRITICAL
 * @param category 分类
 * @param summary  摘要（保留用户诉求）
 * @param tags     标签，可为空
 */
public record TicketDraftResponse(String title, String priority, String category, String summary, List<String> tags) {
}
