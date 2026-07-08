package com.lrj.platform.conversation.grounding;

/**
 * Faithfulness 打分（RAGAS 风格）：给定渲染后的检索来源与答案，返回 0..1 的支撑度分数
 * （1=每条事实断言都能在来源找到依据；0=全是来源没有的内容）。
 *
 * <p>LLM 藏在该函数式接口后（对齐 knowledge {@code RelevanceScorer}），使 {@link LlmGroundingChecker}
 * 纯逻辑可单测、不连模型。
 */
@FunctionalInterface
public interface FaithfulnessScorer {

    double score(String renderedSources, String answer);
}
