package com.lrj.platform.conversation;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

/**
 * 供 workflow-service 复用的对话/抽取能力 AiService（C2）。langchain4j spring starter 自动装配注入
 * 网关唯一 ChatModel（{@code platform-gateway-client} 提供）。
 *
 * <p>由 {@link WorkflowSupportController} 暴露成 {@code /conversation/workflow/**} 端点，
 * workflow 经 HTTP 调用后彻底断开对本地 ChatModel/gateway-client 的直接依赖。
 * LLM 藏在本可 mock 接口后，controller 单测只需 mock 本接口，无需真实模型。
 */
@AiService
public interface WorkflowAssistant {

    /** 退款请求受理/通过答复生成。 */
    @SystemMessage("""
            你是企业客服助手。用户提交的退款相关请求现已可以处理。
            请用简洁、礼貌的中文写一段确认答复，明确告知用户退款将被处理。只输出答复正文，不要额外说明。
            """)
    @UserMessage("用户原始请求：{{it}}")
    String resolveReply(String message);

    /** 从用户消息结构化抽取退款工单。priority 取 LOW/MEDIUM/HIGH/CRITICAL；无法判断时从严取 HIGH。 */
    @SystemMessage("""
            你是退款工单抽取助手。阅读用户消息，抽取一张结构化工单：
            - title：简短工单标题
            - priority：LOW / MEDIUM / HIGH / CRITICAL 之一。涉及投诉、金额较大、长期未解决、资金未到账等风险信号时取 HIGH 或以上；无法判断时从严取 HIGH，切勿轻易判 LOW。
            - category：分类（如 refund、complaint 等）
            - summary：保留用户诉求的摘要
            - tags：关键词标签列表，可为空
            """)
    @UserMessage("用户消息：{{it}}")
    TicketDraft extractTicket(String message);
}
