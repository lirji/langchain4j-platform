package com.lrj.platform.conversation.grounding;

import com.lrj.platform.conversation.RagPromptAugmenter;
import com.lrj.platform.protocol.knowledge.KnowledgeHit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grounding 事后校验的纯静态规则（对齐单体 {@code GroundingService} 的 Layer0/辅助逻辑，与 {@code PiiRedactor} 同风格）。
 * 无状态、无 LLM、可确定性单测。
 */
public final class GroundingRules {

    private GroundingRules() {
    }

    /** 答案里的引用标注：{@code [doc=文件名#片段号]}。 */
    private static final Pattern CITATION = Pattern.compile("\\[doc=([^\\]]+)\\]");

    /** 提取 0..1 小数（复用 knowledge rerank 的解析口径）。 */
    private static final Pattern SCORE = Pattern.compile("(?<!\\d)(0(?:\\.\\d+)?|1(?:\\.0+)?)");

    /**
     * 诚实拒答标记：命中任一即视为「无事实断言」，跳过 grounding 打分。
     * 单体经验：弱模型会把这类拒答误评为 0.0，故需白名单跳过。
     */
    private static final List<String> ABSTENTION_MARKERS = List.of(
            "未在文档中找到", "资料里没有提到", "资料中没有提到", "资料里未提到",
            "未找到相关内容", "没有找到相关内容", "文档中未提到", "文档中没有提到");

    /** Layer0：答案引用了但不在检索来源集合里的 id（伪造/幻觉引用）；去重保序。 */
    public static List<String> fabricatedCitations(String answer, Set<String> sourceIds) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        Set<String> fabricated = new LinkedHashSet<>();
        Matcher m = CITATION.matcher(answer);
        while (m.find()) {
            String id = m.group(1).trim();
            if (!id.isEmpty() && !sourceIds.contains(id)) {
                fabricated.add(id);
            }
        }
        return List.copyOf(fabricated);
    }

    /** 从命中列表算出来源 id 集合（与注入上下文块的 {@code <source id=...>} 一致）。 */
    public static Set<String> sourceIds(List<KnowledgeHit> sources) {
        Set<String> ids = new LinkedHashSet<>();
        for (KnowledgeHit hit : sources) {
            ids.add(RagPromptAugmenter.sourceId(hit));
        }
        return ids;
    }

    /** 是否为诚实拒答（无可核实事实断言 → 跳过打分）。 */
    public static boolean isAbstention(String answer) {
        if (answer == null) {
            return false;
        }
        for (String marker : ABSTENTION_MARKERS) {
            if (answer.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 把命中渲染为 faithfulness 判官可读的 {@code <source id="..">...</source>} 文本。 */
    public static String renderSources(List<KnowledgeHit> sources) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeHit hit : sources) {
            String text = hit.text() == null ? "" : hit.text().trim();
            sb.append("<source id=\"").append(RagPromptAugmenter.sourceId(hit)).append("\">\n")
                    .append(text).append("\n</source>\n");
        }
        return sb.toString();
    }

    /** 从模型输出解析首个 0..1 小数；失败返回 0。 */
    public static double parseScore(String modelOutput) {
        if (modelOutput == null) {
            return 0.0;
        }
        Matcher m = SCORE.matcher(modelOutput.trim());
        if (m.find()) {
            try {
                return Math.max(0.0, Math.min(1.0, Double.parseDouble(m.group(1))));
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    /** warn 模式追加到答案末尾的可信度提示后缀（对齐单体文案）。 */
    public static String warningSuffix(List<String> warnings) {
        return "\n\n⚠️ 可信度提示：" + String.join("；", warnings) + "。请以原始资料为准。";
    }
}
