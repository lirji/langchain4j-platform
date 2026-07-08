package com.lrj.platform.knowledge.query;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * LLM 查询扩展（移植单体 {@code ExpandingQueryTransformer}）：用模型把原 query 改写成 N 个近义变体，
 * 与原 query 一起多路召回。生成经 {@link UnaryOperator} 抽象注入，改写→变体解析逻辑可确定性单测。
 *
 * <p>模型每行输出一个变体；解析后去重、去空、限制到 {@code maxVariants}，并始终保留原 query 在首位。
 */
public class LlmQueryExpander implements QueryExpander {

    private final UnaryOperator<String> rewriter;
    private final int maxVariants;

    public LlmQueryExpander(UnaryOperator<String> rewriter, int maxVariants) {
        this.rewriter = rewriter;
        this.maxVariants = Math.max(1, maxVariants);
    }

    @Override
    public List<String> expand(String query) {
        if (query == null || query.isBlank()) {
            return List.of(query);
        }
        Set<String> out = new LinkedHashSet<>();
        out.add(query.trim());
        try {
            String raw = rewriter.apply(prompt(query));
            if (raw != null) {
                for (String line : raw.split("\\r?\\n")) {
                    String v = clean(line);
                    if (!v.isBlank()) {
                        out.add(v);
                    }
                    if (out.size() >= maxVariants) {
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            // 扩展失败降级为单 query（不因扩展故障影响正常检索）
            return List.of(query.trim());
        }
        return new ArrayList<>(out);
    }

    /** 去掉行首的序号 / 项目符号 / 引号等噪声。 */
    private static String clean(String line) {
        if (line == null) {
            return "";
        }
        return line.trim()
                .replaceFirst("^\\s*(\\d+[.)、]|[-*•])\\s*", "")
                .replaceAll("^[\"'`]+|[\"'`]+$", "")
                .trim();
    }

    static String prompt(String query) {
        return """
                请把下面的问题改写成 3 个语义等价但措辞不同的检索查询（同义替换 / 更正式或更口语 / 关键词展开），
                每行一个，只输出改写结果，不要编号、不要解释。

                原问题：%s
                """.formatted(query);
    }
}
