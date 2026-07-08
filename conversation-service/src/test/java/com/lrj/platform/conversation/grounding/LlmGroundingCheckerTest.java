package com.lrj.platform.conversation.grounding;

import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F3.5 grounding 确定性单测：faithfulness 打分藏在 {@link FaithfulnessScorer} 桩后，不连模型。
 */
class LlmGroundingCheckerTest {

    private static final double THRESHOLD = 0.7;

    private static KnowledgeHit hit(String displayName, String index, String text) {
        return new KnowledgeHit("id", 0.9, "doc", displayName, "cat", index, text, "hybrid");
    }

    private static final List<KnowledgeHit> SOURCES = List.of(hit("guide.md", "2", "退款政策需主管审批。"));

    @Test
    void highScore_grounded_noSuffix() {
        LlmGroundingChecker checker = new LlmGroundingChecker((s, a) -> 0.95, THRESHOLD);
        GroundingResult r = checker.verify("退款需要主管审批。", SOURCES);

        assertThat(r.grounded()).isTrue();
        assertThat(r.warnings()).isEmpty();
        assertThat(r.answer()).isEqualTo("退款需要主管审批。"); // 未追加后缀
        assertThat(r.score()).isEqualTo(0.95);
    }

    @Test
    void lowScore_appendsWarningSuffix() {
        LlmGroundingChecker checker = new LlmGroundingChecker((s, a) -> 0.2, THRESHOLD);
        GroundingResult r = checker.verify("退款无需任何审批，随时自助退。", SOURCES);

        assertThat(r.grounded()).isFalse();
        assertThat(r.answer()).startsWith("退款无需任何审批，随时自助退。")
                .contains("⚠️ 可信度提示")
                .contains("支撑度 0.20 < 0.70");
        assertThat(r.warnings()).anyMatch(w -> w.contains("支撑度"));
    }

    @Test
    void abstention_passesThrough() {
        LlmGroundingChecker checker = new LlmGroundingChecker((s, a) -> {
            throw new AssertionError("scorer should not be called for abstention");
        }, THRESHOLD);
        GroundingResult r = checker.verify("未在文档中找到相关内容。", SOURCES);

        assertThat(r.grounded()).isTrue();
        assertThat(r.answer()).isEqualTo("未在文档中找到相关内容。");
    }

    @Test
    void noSources_passesThrough() {
        LlmGroundingChecker checker = new LlmGroundingChecker((s, a) -> {
            throw new AssertionError("scorer should not be called without sources");
        }, THRESHOLD);
        GroundingResult r = checker.verify("任意答案", List.of());

        assertThat(r.grounded()).isTrue();
        assertThat(r.answer()).isEqualTo("任意答案");
    }

    @Test
    void fabricatedCitation_warnsEvenWhenScoreHigh() {
        LlmGroundingChecker checker = new LlmGroundingChecker((s, a) -> 0.99, THRESHOLD);
        // 引用了不存在的来源 ghost.md#9（真实来源只有 guide.md#2）
        GroundingResult r = checker.verify("依据 [doc=ghost.md#9] 可自助退款。", SOURCES);

        assertThat(r.grounded()).isFalse();
        assertThat(r.answer()).contains("引用了未检索到的来源：ghost.md#9");
    }

    @Test
    void scorerException_skipsLayer1_groundedWhenNoOtherWarning() {
        LlmGroundingChecker checker = new LlmGroundingChecker((s, a) -> {
            throw new RuntimeException("judge down");
        }, THRESHOLD);
        GroundingResult r = checker.verify("正常答案无引用", SOURCES);

        // Layer1 打分失败被跳过；无伪造引用 → 无 warning → 直通
        assertThat(r.grounded()).isTrue();
        assertThat(r.answer()).isEqualTo("正常答案无引用");
    }
}
