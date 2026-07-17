package com.lrj.platform.knowledge.graph;

import java.util.Set;

/**
 * GraphRAG 实体链接接口：把查询文本映射为图中已存在的种子实体表面词（限定租户/类目），
 * 供图检索从这些种子出发做邻域遍历。默认实现见 {@link TokenEntityLinker}。
 */
public interface EntityLinker {

    Set<String> link(String query, String tenantId, String category);
}
