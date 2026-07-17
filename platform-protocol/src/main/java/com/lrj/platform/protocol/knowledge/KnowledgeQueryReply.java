package com.lrj.platform.protocol.knowledge;

import java.util.List;

/**
 * knowledge-service 检索查询的结果（跨服务 DTO）。
 * 回显 {@code query} 查询词与 {@code tenantId} 租户，并携带按相关度排序的 {@link KnowledgeHit} 命中列表 {@code hits}，
 * 是 {@link KnowledgeQueryRequest} 的对应应答。紧凑构造器对 hits 做空值兜底与不可变拷贝。
 */
public record KnowledgeQueryReply(String query, String tenantId, List<KnowledgeHit> hits) {

    public KnowledgeQueryReply {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }
}
