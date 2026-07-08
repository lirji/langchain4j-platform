package com.lrj.platform.conversation.grounding;

import com.lrj.platform.protocol.knowledge.KnowledgeHit;

import java.util.List;

/**
 * Grounding 事后校验（对齐单体 {@code ai/grounding}）：把 LLM 答案与本轮 RAG 来源核对，
 * warn 模式追加可信度提示后缀（不改写、不拒答）。默认 {@link NoopGroundingChecker} 直通，零回归。
 */
public interface GroundingChecker {

    /**
     * @param answer  LLM 原始答案
     * @param sources 本轮真正注入上下文的检索来源（来自 {@code RagPromptAugmenter.RagContext#hits}）
     * @return 校验结果；无来源 / 拒答 / 未开启时直通原答案
     */
    GroundingResult verify(String answer, List<KnowledgeHit> sources);
}
