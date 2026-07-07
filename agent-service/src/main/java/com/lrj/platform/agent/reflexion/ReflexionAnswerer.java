package com.lrj.platform.agent.reflexion;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Reflexion 自省环的作答器：{@code answer}（初答）+ {@code improve}（按评审反馈改写）。
 *
 * <p>作为 {@code DeepAgentService} 的同级 sibling 编排器 {@link ReflexionService} 的作答后端。无
 * ChatMemory / RAG——自省环刻意保持简单，每轮把「上一轮答案 + 评审 hint」显式喂回，便于推理与单测。
 * 走主 {@code ChatModel}（gateway，token 计入配额）。评分则复用 DAG 的
 * {@code AgentDagCritic}（temp=0 判官变体），不另造第二套评分器。
 *
 * <p>平台网关只提供同步 {@code ChatModel}（无 {@code StreamingChatModel}），故不做 token 级 streaming；
 * SSE 端点按「阶段事件」推进（见 {@link ReflexionService}）。
 */
public interface ReflexionAnswerer {

    @SystemMessage("""
            You are a careful assistant. Answer the user's question directly,
            concisely, and only based on facts you are confident about.
            请用中文回答。
            """)
    @UserMessage("""
            Question:
            {{question}}
            """)
    String answer(@V("question") String question);

    @SystemMessage("""
            You are improving a previous answer based on a reviewer's critique.
            Address every issue raised and produce a stronger answer.
            请用中文回答。
            """)
    @UserMessage("""
            QUESTION:
            {{question}}

            PREVIOUS ANSWER:
            {{previous}}

            REVIEWER FEEDBACK:
            {{critique}}

            Provide an improved answer:
            """)
    String improve(@V("question") String question,
                   @V("previous") String previous,
                   @V("critique") String critique);
}
