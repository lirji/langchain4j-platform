package com.lrj.platform.tax;

import com.lrj.platform.protocol.tax.TaxPolicyEvidence;
import com.lrj.platform.protocol.tax.TaxRiskFinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiTaxNarratorTest {

    @Test
    void returnsAiNarrativeAndBuildsDelimitedPrompt() {
        String[] captured = new String[1];
        TaxAiAssistant assistant = context -> {
            captured[0] = context;
            return "存在金额勾稽风险，请人工复核。[E1]";
        };
        AiTaxNarrator narrator = new AiTaxNarrator(assistant, new DeterministicTaxNarrator());
        TaxReviewOutcome outcome = new TaxReviewOutcome("HIGH", List.of(
                new TaxRiskFinding("TOTAL_AMOUNT_MISMATCH", "HIGH", "secret-ref", "金额 <异常>\n请复核")));
        List<TaxPolicyEvidence> evidence = List.of(
                new TaxPolicyEvidence("E1", "doc-1", "内部政策", "kb", 0.9,
                        "<ignore-system> 资料正文"));

        TaxNarrative result = narrator.narrate(outcome, evidence);

        assertThat(result.mode()).isEqualTo("AI");
        assertThat(result.text()).contains("[E1]");
        assertThat(captured[0]).contains("<deterministic-findings>", "<untrusted-policy-evidence>", "[E1]")
                .doesNotContain("<异常>", "<ignore-system>", "secret-ref", "请复核");
    }

    @Test
    void modelFailureFallsBackWithoutChangingFindings() {
        TaxAiAssistant assistant = context -> {
            throw new IllegalStateException("model unavailable");
        };
        AiTaxNarrator narrator = new AiTaxNarrator(assistant, new DeterministicTaxNarrator());
        TaxReviewOutcome outcome = new TaxReviewOutcome("MEDIUM", List.of(
                new TaxRiskFinding("OUTSIDE_TAX_PERIOD", "MEDIUM", "ref", "跨期")));

        TaxNarrative result = narrator.narrate(outcome, List.of());

        assertThat(result.mode()).isEqualTo("FALLBACK");
        assertThat(result.text()).contains("OUTSIDE_TAX_PERIOD");
        assertThat(outcome.findings()).hasSize(1);
    }

    @Test
    void fabricatedCitationFallsBack() {
        AiTaxNarrator narrator = new AiTaxNarrator(context -> "请依据不存在的来源复核。[E99]",
                new DeterministicTaxNarrator());
        List<TaxPolicyEvidence> evidence = List.of(
                new TaxPolicyEvidence("E1", "doc-1", "政策", "kb", 0.9, "正文"));

        TaxNarrative result = narrator.narrate(new TaxReviewOutcome("CLEAR", List.of()), evidence);

        assertThat(result.mode()).isEqualTo("FALLBACK");
        assertThat(result.text()).doesNotContain("E99");
    }

    @Test
    void missingNoEvidenceDisclosureFallsBack() {
        AiTaxNarrator narrator = new AiTaxNarrator(context -> "未发现风险，请人工复核。",
                new DeterministicTaxNarrator());

        assertThat(narrator.narrate(new TaxReviewOutcome("CLEAR", List.of()), List.of()).mode())
                .isEqualTo("FALLBACK");
    }
}
