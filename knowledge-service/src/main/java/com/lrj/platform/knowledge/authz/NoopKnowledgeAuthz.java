package com.lrj.platform.knowledge.authz;

import java.util.Set;

/** 关闭态默认实现: 不双写、不过滤。保证 app.rag.authz.mode=disabled（默认）时与接入前逐字一致。 */
public class NoopKnowledgeAuthz implements KnowledgeAuthz {

    @Override
    public AuthzMode mode() {
        return AuthzMode.DISABLED;
    }

    @Override
    public void onDocumentCreated(String tenantId, String docId, String ownerUserId, String departmentId) {
        // no-op
    }

    @Override
    public void onDocumentDeleted(String tenantId, String docId) {
        // no-op
    }

    @Override
    public void grantDocumentViewer(String tenantId, String docId, String userId) {
        // no-op
    }

    @Override
    public void revokeDocumentViewer(String tenantId, String docId, String userId) {
        // no-op
    }

    @Override
    public boolean checkDocument(String tenantId, String userId, String docId, String permission) {
        return true;
    }

    @Override
    public Set<String> filterReadable(String tenantId, String userId, Set<String> docIds) {
        return docIds;
    }
}
