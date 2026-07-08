package com.lrj.platform.eval.retrieval;

import java.util.List;

/**
 * 纯函数：把「检索回的有序 id 列表」+「标注的相关 id 集合」算成经典 IR 指标 ——
 * Recall@k / Precision@k / MRR / Hit@k（迁移单体 {@code eval/retrieval/RetrievalMetrics}）。
 * 无状态、可确定性单测、不经 LLM。
 *
 * <p>补的是 LLM-Judge passRate 一直没覆盖的<strong>召回层</strong>：passRate 测「答对没」（生成质量，混检索+LLM 两层），
 * 本类测「相关文档有没有被捞回」（纯检索质量）。
 *
 * <p><b>id 匹配规则</b>（对 chunk 切分漂移鲁棒）：标注 id 分两种粒度 ——
 * 文件级（不含 {@code #}）比检索 id 的文件部分（{@code #} 前）；精确级（含 {@code #}）需全等。
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /** 单 case 的检索指标。recall/precision/mrr ∈ [0,1]；hit = 是否至少命中一个相关文档。 */
    public record CaseMetrics(double recall, double precision, double mrr, boolean hit,
                              int relevantTotal, int relevantRetrieved, int retrievedTotal) {
    }

    /** 判断一个检索回的 id 是否命中某个标注 id。 */
    public static boolean matches(String retrievedId, String relevantId) {
        if (retrievedId == null || relevantId == null) {
            return false;
        }
        if (retrievedId.equals(relevantId)) {
            return true;
        }
        if (relevantId.indexOf('#') < 0) {
            return filePart(retrievedId).equals(relevantId); // 文件级标注：比检索 id 的文件部分
        }
        return false;
    }

    /** {@code file.md#3} → {@code file.md}；无 {@code #} 时原样返回。 */
    public static String filePart(String id) {
        if (id == null) {
            return "";
        }
        int h = id.indexOf('#');
        return h < 0 ? id : id.substring(0, h);
    }

    /**
     * 算一个 case 的四个指标。
     *
     * @param retrievedIds 检索器回的 id，<strong>按相关性降序</strong>（rank 敏感的 MRR 依赖顺序）
     * @param relevantIds  该 query 的标注相关 id（去重后当分母）
     */
    public static CaseMetrics compute(List<String> retrievedIds, List<String> relevantIds) {
        List<String> retrieved = retrievedIds == null ? List.of() : retrievedIds;
        List<String> relevant = relevantIds == null ? List.of() : relevantIds.stream().distinct().toList();

        int relevantTotal = relevant.size();
        int retrievedTotal = retrieved.size();

        int covered = 0;
        for (String g : relevant) {
            if (retrieved.stream().anyMatch(r -> matches(r, g))) {
                covered++;
            }
        }
        double recall = relevantTotal == 0 ? 0.0 : (double) covered / relevantTotal;

        int hitRetrieved = 0;
        int firstHitRank = -1;
        for (int i = 0; i < retrieved.size(); i++) {
            final String r = retrieved.get(i);
            boolean isRel = relevant.stream().anyMatch(g -> matches(r, g));
            if (isRel) {
                hitRetrieved++;
                if (firstHitRank < 0) {
                    firstHitRank = i + 1; // 1-based
                }
            }
        }
        double precision = retrievedTotal == 0 ? 0.0 : (double) hitRetrieved / retrievedTotal;
        double mrr = firstHitRank < 0 ? 0.0 : 1.0 / firstHitRank;
        boolean hit = covered > 0;

        return new CaseMetrics(recall, precision, mrr, hit, relevantTotal, covered, retrievedTotal);
    }
}
