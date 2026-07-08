package com.lrj.platform.conversation.routing;

import dev.langchain4j.model.output.structured.Description;

/**
 * 分类器的结构化输出（对齐单体 {@code RouteDecision}）。{@link Description} 进 JSON Schema 约束模型输出。
 */
public record RouteDecision(
        @Description("路由类别之一：RAG=问项目文档/知识库的具体内容需检索；TOOL=需调用注册工具如当前时间/日期；CHAT=通用对话/概念解释/代码示例，模型自身知识即可")
        RouteKind kind,
        @Description("一句话说明为何这样分类") String reason) {
}
