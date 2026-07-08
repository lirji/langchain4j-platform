package com.lrj.platform.conversation.routing;

/**
 * 路由对话结果：分类决策 + 最终回复 + 分类/回答耗时（ms），供 {@code /chat/auto} 返回与可观测。
 */
public record RoutedReply(RouteDecision decision, String reply, long classifyMs, long answerMs) {
}
