package com.lrj.platform.knowledge.graph;

import java.util.List;
import java.util.Set;

/**
 * GraphRAG 三元组存储与图遍历接口：写入三元组、从种子实体做多跳邻域遍历（{@link #neighbors}）、
 * 列出实体、按 sourceId 前缀删除、统计规模；所有操作按 tenantId（及可选 category）隔离。
 * 实现有内存版 {@link InMemoryGraphStore} 与 JDBC 版 {@link JdbcGraphStore}。
 */
public interface GraphStore {

    void add(List<Triple> triples);

    List<Triple> neighbors(Set<String> seedSurfaces, int maxHops, String tenantId, String category);

    Set<String> entities(String tenantId, String category);

    int removeBySourcePrefix(String tenantId, String sourceIdPrefix);

    int size();
}
