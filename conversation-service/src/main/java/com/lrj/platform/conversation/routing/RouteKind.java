package com.lrj.platform.conversation.routing;

/**
 * 路由类别（对齐单体 {@code ai/routing/RouteKind}）：
 * <ul>
 *   <li>{@code RAG} —— 指向项目文档/知识库的具体内容，需检索才能正确回答；</li>
 *   <li>{@code TOOL} —— 需调用注册工具或权威业务系统（订单、当前时间、日期等）；</li>
 *   <li>{@code CHAT} —— 纯对话/概念解释/通用知识，模型自身训练知识即可。</li>
 * </ul>
 */
public enum RouteKind {
    RAG, TOOL, CHAT
}
