package com.lrj.platform.knowledge.query;

import java.util.List;

/**
 * 查询扩展 SPI（移植单体 {@code ExpandingQueryTransformer}）：把 1 个 query 扩成 N 个语义等价/近义变体，
 * 多路召回后融合，缓解「用户措辞与文档措辞不一致」导致的召回缺失。
 *
 * <p>默认 {@link NoopQueryExpander}（只返回原 query）；开启后返回「原 query + 若干变体」。
 */
public interface QueryExpander {

    /** 返回用于检索的 query 列表（始终包含原 query）；关闭时即 {@code [query]}。 */
    List<String> expand(String query);
}
