package com.lrj.platform.knowledge.authz;

import com.lrj.authz.sdk.CheckAccess;
import com.lrj.platform.security.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * {@code @CheckAccess} 声明式判权示范（enforce 专用）。
 *
 * <p>文档分享是敏感的授权变更操作，天然强判权（不参与 shadow）。切面从 {@code SubjectResolver} 取当前主体、
 * 从 {@code documentResourceId} 参数取资源 id、对 document 判 {@code edit}；不通过抛 SDK 的
 * {@code AccessDeniedException}（由 {@code AuthzExceptionHandler} 转 403）。
 *
 * <p><strong>注意</strong>：{@code @CheckAccess} 靠 AOP 代理生效，故这些方法必须被<strong>外部 bean</strong>
 * （controller）调用，不能类内 self-invocation。
 */
@Service
@ConditionalOnProperty(name = "app.rag.authz.mode", havingValue = "enforce")
public class KnowledgeAccessApplicationService {

    private final KnowledgeAuthz knowledgeAuthz;

    public KnowledgeAccessApplicationService(KnowledgeAuthz knowledgeAuthz) {
        this.knowledgeAuthz = knowledgeAuthz;
    }

    /**
     * 把文档分享给某用户（授 viewer）。要求调用者对该文档有 edit 权。
     *
     * @param documentResourceId 完整 SpiceDB 资源 id（{@code <tenantId>_<docId>}），由 controller 构造后传入
     * @param docId              裸 docId（用于业务写关系）
     * @param granteeUserId      被授权用户
     */
    @CheckAccess(permission = "edit", resourceType = "document", resourceIdParam = "documentResourceId", fullyConsistent = true)
    public void shareDocument(String documentResourceId, String docId, String granteeUserId) {
        knowledgeAuthz.grantDocumentViewer(TenantContext.current().tenantId(), docId, granteeUserId);
    }

    /** 撤销某用户对文档的 viewer。要求调用者对该文档有 edit 权。 */
    @CheckAccess(permission = "edit", resourceType = "document", resourceIdParam = "documentResourceId", fullyConsistent = true)
    public void unshareDocument(String documentResourceId, String docId, String granteeUserId) {
        knowledgeAuthz.revokeDocumentViewer(TenantContext.current().tenantId(), docId, granteeUserId);
    }
}
