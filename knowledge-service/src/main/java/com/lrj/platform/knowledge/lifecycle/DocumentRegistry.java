package com.lrj.platform.knowledge.lifecycle;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-tenant 文档级元数据注册表：{@code tenantId -> docId -> DocumentInfo}。
 * EmbeddingStore 存 segment 级数据（向量），这里存"一个文档=多 segment"的逻辑视图，
 * 列表 / 详情 / 删除 / 版本管理从这里读。
 *
 * <p>两个实现按 {@code app.rag.registry.store} 切换：
 * <ul>
 *   <li>{@link InMemoryDocumentRegistry}（默认）— 进程内存，重启即丢。单实例 / 本地开发够用。</li>
 *   <li>{@link RedisDocumentRegistry}（{@code redis}）— 持久化，重启 / 多实例后 {@code GET /rag/documents}
 *       仍列得出。生产知识库（kb profile）用这个，跟持久化向量库（Milvus）配套。</li>
 * </ul>
 *
 * <p>注意：registry 与 EmbeddingStore 是两套存储，需配置上保持一致 —— 持久化向量库就该配持久化
 * registry，否则会出现"向量还在但列表空"（重启后 list 返回 [] 但 RAG 仍答得出）的割裂。
 */
public interface DocumentRegistry {

    void put(DocumentInfo info);

    /** 新版本只允许从前一版本提交；相同版本重放不覆盖，旧版本不能回退可见性。 */
    default boolean commitVersion(DocumentInfo info) {
        throw new UnsupportedOperationException("registry does not support atomic version commit");
    }

    /** 按版本原子退役并保留墓碑；重复退役幂等，旧worker不得复活此文档ID。 */
    default boolean retireVersion(String tenantId, String docId, long expectedVersion) {
        throw new UnsupportedOperationException("registry does not support atomic retirement");
    }

    Optional<DocumentInfo> get(String tenantId, String docId);

    List<DocumentInfo> list(String tenantId);

    Optional<DocumentInfo> remove(String tenantId, String docId);

    /** 维护扫描按页推进；空页不代表结束，游标不持有连接，可跨调度轮次使用。 */
    MaintenanceScan maintenanceScan(int pageSize);

    /** 只有全部sink成功的连续版本才记为已清理；检查点与registry采用同一持久化级别。 */
    long cleanedThrough(String tenantId, String docId);

    /** 条件推进避免并发worker、文档重建或版本变更写入过期检查点。 */
    boolean markCleaned(DocumentInfo observed, long previous, long cleaned);

    interface MaintenanceScan {
        List<DocumentInfo> nextPage();
        boolean exhausted();
    }

    /** 仅给 actuator / debug 用 —— 返回全部租户的浅快照。 */
    Map<String, Collection<DocumentInfo>> snapshotAll();
}
