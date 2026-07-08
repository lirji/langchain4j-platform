package com.lrj.platform.conversation.grounding;

import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingRulesTest {

    private static KnowledgeHit hit(String displayName, String index, String text) {
        return new KnowledgeHit("id", 0.9, "doc", displayName, "cat", index, text, "hybrid");
    }

    @Test
    void sourceIds_matchDisplayNameHashIndex() {
        Set<String> ids = GroundingRules.sourceIds(List.of(hit("guide.md", "2", "t1"), hit("faq.md", "0", "t2")));
        assertThat(ids).containsExactly("guide.md#2", "faq.md#0");
    }

    @Test
    void fabricatedCitations_returnsCitationsNotInSources() {
        String answer = "根据 [doc=guide.md#2] 需要审批，另见 [doc=ghost.md#9]。";
        List<String> fabricated = GroundingRules.fabricatedCitations(answer, Set.of("guide.md#2"));
        assertThat(fabricated).containsExactly("ghost.md#9");
    }

    @Test
    void fabricatedCitations_allValid_returnsEmpty() {
        String answer = "见 [doc=guide.md#2] 与 [doc=faq.md#0]。";
        assertThat(GroundingRules.fabricatedCitations(answer, Set.of("guide.md#2", "faq.md#0"))).isEmpty();
    }

    @Test
    void fabricatedCitations_noCitations_returnsEmpty() {
        assertThat(GroundingRules.fabricatedCitations("普通回答无引用", Set.of("guide.md#2"))).isEmpty();
        assertThat(GroundingRules.fabricatedCitations(null, Set.of("x"))).isEmpty();
    }

    @Test
    void fabricatedCitations_dedupsPreservingOrder() {
        String answer = "[doc=x#1] ... [doc=x#1] ... [doc=y#2]";
        assertThat(GroundingRules.fabricatedCitations(answer, Set.of())).containsExactly("x#1", "y#2");
    }

    @Test
    void isAbstention_detectsHonestRefusal() {
        assertThat(GroundingRules.isAbstention("未在文档中找到相关内容")).isTrue();
        assertThat(GroundingRules.isAbstention("资料里没有提到这个")).isTrue();
        assertThat(GroundingRules.isAbstention("Milvus 是向量数据库")).isFalse();
        assertThat(GroundingRules.isAbstention(null)).isFalse();
    }

    @Test
    void parseScore_extractsFirstFloat() {
        assertThat(GroundingRules.parseScore("0.85")).isEqualTo(0.85);
        assertThat(GroundingRules.parseScore("支撑度是 0.3 分")).isEqualTo(0.3);
        assertThat(GroundingRules.parseScore("1")).isEqualTo(1.0);
        assertThat(GroundingRules.parseScore("乱码无数字")).isEqualTo(0.0);
        assertThat(GroundingRules.parseScore(null)).isEqualTo(0.0);
    }

    @Test
    void renderSources_wrapsInSourceTags() {
        String rendered = GroundingRules.renderSources(List.of(hit("guide.md", "2", "退款需审批")));
        assertThat(rendered).contains("<source id=\"guide.md#2\">").contains("退款需审批").contains("</source>");
    }

    @Test
    void warningSuffix_joinsWarnings() {
        String suffix = GroundingRules.warningSuffix(List.of("A", "B"));
        assertThat(suffix).contains("⚠️ 可信度提示：A；B").contains("请以原始资料为准");
    }
}
