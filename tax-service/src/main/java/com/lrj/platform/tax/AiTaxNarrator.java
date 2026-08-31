package com.lrj.platform.tax;

import com.lrj.platform.protocol.tax.TaxPolicyEvidence;
import com.lrj.platform.protocol.tax.TaxRiskFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 调用模型生成说明；任何异常或空响应都回退到确定性说明。 */
final class AiTaxNarrator implements TaxNarrator {

    private static final Logger log = LoggerFactory.getLogger(AiTaxNarrator.class);
    private static final Pattern CITATION = Pattern.compile("\\[E\\d+]");
    private static final int MAX_NARRATIVE_CHARS = 300;
    private final TaxAiAssistant assistant;
    private final DeterministicTaxNarrator fallback;

    AiTaxNarrator(TaxAiAssistant assistant, DeterministicTaxNarrator fallback) {
        this.assistant = assistant;
        this.fallback = fallback;
    }

    @Override
    public TaxNarrative narrate(TaxReviewOutcome outcome, List<TaxPolicyEvidence> evidence) {
        try {
            String text = assistant.explain(prompt(outcome, evidence));
            if (!acceptable(text, evidence)) return fallback.narrate(outcome, evidence);
            return new TaxNarrative(text.strip(), "AI");
        } catch (RuntimeException ex) {
            log.warn("tax narrative generation failed; using deterministic fallback: {}", ex.toString());
            return fallback.narrate(outcome, evidence);
        }
    }

    private static boolean acceptable(String text, List<TaxPolicyEvidence> evidence) {
        if (text == null || text.isBlank() || text.strip().length() > MAX_NARRATIVE_CHARS) return false;
        Set<String> allowed = evidence.stream()
                .map(item -> "[" + item.citationId() + "]")
                .collect(java.util.stream.Collectors.toSet());
        Matcher matcher = CITATION.matcher(text);
        boolean cited = false;
        while (matcher.find()) {
            cited = true;
            if (!allowed.contains(matcher.group())) return false;
        }
        if (allowed.isEmpty()) return !cited && text.contains("未检索到政策证据");
        return cited;
    }

    static String prompt(TaxReviewOutcome outcome, List<TaxPolicyEvidence> evidence) {
        StringBuilder prompt = new StringBuilder("<deterministic-findings>\n");
        prompt.append("overallRisk=").append(outcome.overallRisk()).append('\n');
        for (TaxRiskFinding finding : outcome.findings()) {
            prompt.append("code=").append(finding.code())
                    .append(" severity=").append(finding.severity()).append('\n');
        }
        prompt.append("</deterministic-findings>\n<untrusted-policy-evidence>\n");
        for (TaxPolicyEvidence item : evidence) {
            prompt.append('[').append(item.citationId()).append("] ")
                    .append(clean(item.displayName())).append(": ")
                    .append(clean(item.excerpt())).append('\n');
        }
        return prompt.append("</untrusted-policy-evidence>").toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('<', ' ').replace('>', ' ').replace('\n', ' ').strip();
    }
}
