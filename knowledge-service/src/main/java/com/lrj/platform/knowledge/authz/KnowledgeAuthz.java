package com.lrj.platform.knowledge.authz;

import java.util.Set;

/**
 * 知识库细粒度授权钩子 (接入 auth-platform)。默认 {@link NoopKnowledgeAuthz} —— disabled 时行为与接入前逐字一致。
 * 开关: app.rag.authz.mode=shadow|enforce 时由 {@code KnowledgeAuthzConfig} 注入 {@link RealKnowledgeAuthz}。
 *
 * <p>写路径 (DocumentService) 建/删文档时双写 SpiceDB 关系元组;
 * 读路径 (KnowledgeQueryService) 融合后、重排前, 按当前用户对候选 docId 做 view 判权过滤。
 */
public interface KnowledgeAuthz {

    /** 运行模式 (DISABLED/SHADOW/ENFORCE)。 */
    AuthzMode mode();

    /** 是否参与 (shadow/enforce 均为 true; 关时读路径不过滤、写路径不双写)。 */
    default boolean enabled() {
        return mode() != AuthzMode.DISABLED;
    }

    /** 文档创建: 写 owner + parent_space 关系。 */
    void onDocumentCreated(String tenantId, String docId, String ownerUserId);

    /** 文档删除: 清该文档的全部关系。 */
    void onDocumentDeleted(String tenantId, String docId);

    /** 把文档分享给某用户 (授 viewer)。 */
    void grantDocumentViewer(String tenantId, String docId, String userId);

    /** 撤销某用户对文档的 viewer。 */
    void revokeDocumentViewer(String tenantId, String docId, String userId);

    /**
     * 单文档判权（统一 mode，供单资源 get/delete 用）。
     * DISABLED/SHADOW 恒放行（true）；SHADOW 本该拒绝时打点 would_deny。ENFORCE 返回真实结果。
     *
     * @param permission 如 {@code "view"}（读）/ {@code "edit"}（删/改）
     */
    boolean checkDocument(String tenantId, String userId, String docId, String permission);

    /** 返回 docIds 中 userId 可 view 的子集; 关闭时返回全集 (不过滤)。 */
    Set<String> filterReadable(String tenantId, String userId, Set<String> docIds);
}
