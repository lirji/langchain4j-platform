package com.lrj.platform.knowledge.ingest.sync;

import com.lrj.platform.security.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;

/** 管理入口复用现有ingestions路由；仅绑定的租户执行身份可查看或手动推进自己的来源。 */
@RestController
@RequestMapping("/rag/ingestions/sources")
@ConditionalOnExpression("${app.rag.http-sync.enabled:false} && "
        + "('${app.rag.runtime.role:combined}' == 'combined' || '${app.rag.runtime.role:combined}' == 'ingest-api')")
public class KnowledgeSyncController {
    private final KnowledgeSyncProperties properties;
    private final KnowledgeSyncService service;
    public KnowledgeSyncController(KnowledgeSyncProperties properties, KnowledgeSyncService service) {
        this.properties = properties; this.service = service;
    }
    /** 列表仅枚举当前身份绑定的配置来源，不泄露其他租户来源的存在。 */
    @GetMapping
    public List<SyncData.Status> list() {
        var actor = actor();
        return properties.sources().stream().filter(source -> owns(source, actor)).map(service::status).toList();
    }
    /** 返回脱敏进度；游标与内容不作为公开协议的一部分。 */
    @GetMapping("/{id}")
    public SyncData.Status get(@PathVariable String id) { return service.status(source(id)); }
    /** 来源映射便于运营定位外部记录及知识文档，隔离规则与来源状态一致。 */
    @GetMapping("/{id}/document")
    public SyncData.DocumentState document(@PathVariable String id, @RequestParam String externalId) {
        var source = source(id);
        if (externalId.isBlank() || externalId.length() > 256) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var result = service.document(source, externalId);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return result;
    }
    /** 手动触发遵守相同租约和退避，不允许调用方切换URL、租户或执行用户。 */
    @PostMapping("/{id}/poll")
    public ResponseEntity<SyncData.Status> poll(@PathVariable String id) {
        var source = source(id); service.poll(source);
        return ResponseEntity.accepted().body(service.status(source));
    }
    private KnowledgeSyncProperties.Source source(String id) {
        var actor = actor();
        return properties.sources().stream().filter(value -> value.id().equals(id) && owns(value, actor))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    private static TenantContext.Tenant actor() {
        var actor = TenantContext.current();
        if (!actor.hasScope("ingest")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return actor;
    }
    private static boolean owns(KnowledgeSyncProperties.Source source, TenantContext.Tenant actor) {
        return source.tenantId().equals(actor.tenantId()) && source.userId().equals(actor.userId());
    }
    /** 只提供稳定错误码，不返回内部堆栈或配置。 */
    @ExceptionHandler(SyncFailure.class)
    public ResponseEntity<Map<String, String>> failure(SyncFailure failure) {
        return ResponseEntity.status(503).body(Map.of("code", failure.code()));
    }
}
