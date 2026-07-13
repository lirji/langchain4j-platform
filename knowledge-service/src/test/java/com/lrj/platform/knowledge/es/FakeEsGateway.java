package com.lrj.platform.knowledge.es;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存 fake ES 网关（测试用，es-hybrid-rerank）。记录 index/delete，返回可编排的 search 结果，
 * 可注入 bulk/search 异常验证降级与 fail-fast。无需真实 ES / Testcontainers。
 */
public class FakeEsGateway implements EsGateway {

    public final List<EsSegmentDocument> indexed = new ArrayList<>();
    public final List<String[]> deleted = new ArrayList<>();
    public int ensureIndexCalls = 0;
    public List<EsSearchHit> searchResult = List.of();
    public RuntimeException bulkError;
    public RuntimeException searchError;
    // 记录最近一次 search 入参，供隔离断言（#9）。
    public String lastSearchTenant;
    public String lastSearchCategory;
    public int lastSearchLimit;

    @Override
    public void ensureIndex() {
        ensureIndexCalls++;
    }

    @Override
    public void bulkUpsert(List<EsSegmentDocument> docs) {
        if (bulkError != null) {
            throw bulkError;
        }
        indexed.addAll(docs);
    }

    @Override
    public void deleteByDoc(String tenantId, String docId) {
        deleted.add(new String[]{tenantId, docId});
    }

    @Override
    public List<EsSearchHit> search(String tenantId, String category, String queryText, int limit) {
        this.lastSearchTenant = tenantId;
        this.lastSearchCategory = category;
        this.lastSearchLimit = limit;
        if (searchError != null) {
            throw searchError;
        }
        return searchResult;
    }
}
