package com.lrj.platform.knowledge.es;

import java.util.List;

/**
 * ES 操作窄端口（阶段3，es-hybrid-rerank）。把 ES 客户端细节收敛在实现里，让索引器 / 检索源可用 fake 脱离真实 ES 单测
 * （项目约定不引 Testcontainers）。真实现 {@code ElasticsearchEsGateway}，测试用内存 fake。
 */
public interface EsGateway {

    /** 幂等确保索引与 mapping（含中文 analyzer）存在。 */
    void ensureIndex();

    /** 批量 upsert（按 {@link EsSegmentDocument#id()} 幂等）。 */
    void bulkUpsert(List<EsSegmentDocument> docs);

    /** 按 tenantId + docId 删除该文档全部 chunk。 */
    void deleteByDoc(String tenantId, String docId);

    /** 全文检索：text 字段 match，tenantId(+category) filter，返回带 BM25 分的命中（相关性降序）。 */
    List<EsSearchHit> search(String tenantId, String category, String queryText, int limit);
}
