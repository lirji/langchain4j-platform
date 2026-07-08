package com.lrj.platform.conversation.grounding;

import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * LLM 版 grounding 校验（warn 模式）：
 * <ul>
 *   <li>Layer0（确定性、零 LLM）：答案引用了检索来源之外的 {@code [doc=ID]} → 伪造引用告警；</li>
 *   <li>Layer1（faithfulness）：{@link FaithfulnessScorer} 打分 &lt; 阈值 → 低支撑度告警。</li>
 * </ul>
 * 有告警则在答案末尾追加可信度提示后缀（不改写正文、不拒答，对齐单体 WARN 模式）。
 * 无来源 / 诚实拒答一律直通。
 */
public class LlmGroundingChecker implements GroundingChecker {

    private static final Logger log = LoggerFactory.getLogger(LlmGroundingChecker.class);

    private final FaithfulnessScorer scorer;
    private final double threshold;

    public LlmGroundingChecker(FaithfulnessScorer scorer, double threshold) {
        this.scorer = scorer;
        this.threshold = threshold;
    }

    @Override
    public GroundingResult verify(String answer, List<KnowledgeHit> sources) {
        if (answer == null || answer.isBlank() || sources == null || sources.isEmpty()) {
            return GroundingResult.passthrough(answer); // 本轮无 RAG 来源，无从校验
        }
        if (GroundingRules.isAbstention(answer)) {
            return GroundingResult.passthrough(answer); // 诚实拒答无事实断言，跳过
        }

        List<String> warnings = new ArrayList<>();

        // Layer0：伪造引用（确定性）
        Set<String> sourceIds = GroundingRules.sourceIds(sources);
        List<String> fabricated = GroundingRules.fabricatedCitations(answer, sourceIds);
        if (!fabricated.isEmpty()) {
            warnings.add("引用了未检索到的来源：" + String.join("、", fabricated));
        }

        // Layer1：faithfulness 打分
        Double score = null;
        try {
            score = scorer.score(GroundingRules.renderSources(sources), answer);
            if (score < threshold) {
                warnings.add(String.format("支撑度 %.2f < %.2f，可能含未被资料支撑的内容", score, threshold));
            }
        } catch (RuntimeException e) {
            log.warn("faithfulness scoring failed, skipping Layer1: {}", e.toString());
        }

        if (warnings.isEmpty()) {
            return new GroundingResult(answer, score, List.of(), true);
        }
        return new GroundingResult(answer + GroundingRules.warningSuffix(warnings), score, List.copyOf(warnings), false);
    }
}
