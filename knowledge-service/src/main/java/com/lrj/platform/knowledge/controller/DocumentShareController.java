package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.authz.KnowledgeAccessApplicationService;
import com.lrj.platform.knowledge.authz.KnowledgeResourceIds;
import com.lrj.platform.security.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 文档分享端点（{@code @CheckAccess} 声明式判权示范，enforce 专用）。
 * <ul>
 *   <li>{@code POST /rag/documents/{docId}/share} body {@code {"userId":"bob"}} —— 授 bob viewer（调用者需 edit 权）。</li>
 *   <li>{@code DELETE /rag/documents/{docId}/share/{userId}} —— 撤销。</li>
 * </ul>
 * 资源 id 由服务端用 {@code <tenantId>_<docId>} 构造，客户端只给裸 docId（不接受完整 tenant-prefixed id）。
 * 判权失败由 {@code AuthzExceptionHandler} 转 403。
 */
@RestController
@ConditionalOnProperty(name = "app.rag.authz.mode", havingValue = "enforce")
public class DocumentShareController {

    private final KnowledgeAccessApplicationService access;

    public DocumentShareController(KnowledgeAccessApplicationService access) {
        this.access = access;
    }

    @PostMapping("/rag/documents/{docId}/share")
    public ResponseEntity<?> share(@PathVariable String docId, @RequestBody(required = false) Map<String, String> body) {
        String grantee = body == null ? null : body.get("userId");
        if (grantee == null || grantee.isBlank()) {
            return ResponseEntity.badRequest().header("X-Error", "userId is required").build();
        }
        access.shareDocument(resourceId(docId), docId, grantee);
        return ResponseEntity.ok(Map.of("docId", docId, "sharedWith", grantee));
    }

    @DeleteMapping("/rag/documents/{docId}/share/{userId}")
    public ResponseEntity<?> unshare(@PathVariable String docId, @PathVariable String userId) {
        access.unshareDocument(resourceId(docId), docId, userId);
        return ResponseEntity.ok(Map.of("docId", docId, "revokedFrom", userId));
    }

    private static String resourceId(String docId) {
        return KnowledgeResourceIds.document(TenantContext.current().tenantId(), docId);
    }
}
