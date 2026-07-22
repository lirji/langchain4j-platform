package com.lrj.platform.conversation.routing;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * Query 分类器（迁移单体 {@code ai/routing/QueryClassifier}）：把用户问题归类为 RAG / TOOL / CHAT。
 * 由 {@link RoutingConfig} 用 temp=0 判官模型程序化构建（分类需确定性）。
 */
public interface QueryClassifier {

    @SystemMessage("""
            你是一个 query 分类器。把用户问题归类为下面三档之一：

            - RAG：问题指向**项目内文档/知识库的具体内容**（提到「文档」「手册」「资料里」「根据上述材料」「本项目里」「配置项」之类），
              需要从文档检索才能正确回答。
            - TOOL：问题需要调用**注册的工具或业务系统**才能正确回答，比如：查询订单号对应的状态/金额/客户/日期，
              或当前时间、日期、距离 X 还有多少天、特定时区时间。订单事实绝不能靠模型猜测。
            - CHAT：纯对话 / 概念解释 / 写代码示例 / 通用知识问答 —— 模型靠自己的训练知识就能回答，
              **既不依赖项目文档，也不需要工具**。

            # 关键反例
            - 「什么是 RAG？」→ CHAT（解释通用概念，不是查文档）
            - 「2024 年的总统是谁」→ CHAT 或 TOOL（看是否有 web search 工具；本项目没有 → CHAT）
            - 「现在几点」→ TOOL（要 currentDateTime）
            - 「查询退款订单 204」→ TOOL（必须查询订单服务）
            - 「根据上述资料，本项目的数据库是什么」→ RAG

            决策不确定时偏向 CHAT（成本最低，没有 false negative 风险）。
            """)
    @UserMessage("分类下面这条 query：\n{{it}}")
    RouteDecision classify(String query);
}
