package com.lrj.platform.protocol.eval;

import java.util.List;

/**
 * 检索评测 case（跨服务契约）：一个 query + 其标注相关文档 id。
 *
 * <p>{@code relevantDocIds} 两种粒度：文件级（不含 {@code #}，如 {@code project-faq.md}，对 chunk 切分漂移鲁棒，推荐）；
 * 精确级（含 {@code #}，如 {@code project-faq.md#2}，钉住具体片段）。
 */
public record RetrievalCase(String id, String question, List<String> relevantDocIds) {
}
