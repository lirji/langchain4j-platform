package com.lrj.platform.knowledge.ingest.sync;

import com.lrj.platform.security.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import static com.lrj.platform.protocol.knowledge.KnowledgeSyncOperations.*;

/** 管理入口复用现有ingestions路由；仅绑定租户执行身份或显式运营者可以管理来源。 */
@RestController
@RequestMapping("/rag/ingestions/sources")
@ConditionalOnExpression("${app.rag.http-sync.enabled:true} && "
        + "('${app.rag.runtime.role:combined}' == 'combined' || '${app.rag.runtime.role:combined}' == 'ingest-api')")
public class KnowledgeSyncController {
    private final KnowledgeSyncProperties properties;
    private final Supplier<KnowledgeSyncService> service;
    /** 未配置来源时只提供空列表；先完成来源授权，再解析可选的采集服务。 */
    @Autowired
    public KnowledgeSyncController(KnowledgeSyncProperties properties,
            ObjectProvider<KnowledgeSyncService> service) {
        this.properties = properties; this.service = service::getObject;
    }
    /** 允许独立控制器验证直接提供采集服务，行为与容器装配相同。 */
    public KnowledgeSyncController(KnowledgeSyncProperties properties, KnowledgeSyncService service) {
        this.properties = properties; this.service = () -> service;
    }
    /** 列表仅枚举当前身份绑定的配置来源，不泄露其他租户来源的存在。 */
    @GetMapping
    public List<Status> list() {
        var actor = actor();
        return properties.sources().stream().filter(source -> owns(source, actor)).map(source -> service.get().status(source)).toList();
    }
    /** 返回脱敏进度；游标与内容不作为公开协议的一部分。 */
    @GetMapping("/{id}")
    public Status get(@PathVariable String id) {
        var source = source(id);
        return service.get().status(source);
    }
    /** 来源映射便于运营定位外部记录及知识文档，隔离规则与来源状态一致。 */
    @GetMapping("/{id}/document")
    public SyncData.DocumentState document(@PathVariable String id, @RequestParam String externalId) {
        var source = source(id);
        if (externalId.isBlank() || externalId.length() > 256) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var result = service.get().document(source, externalId);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return result;
    }
    /** 手动触发提前安排轮询，仍遵守暂停和租约，不允许调用方切换URL、租户或执行用户。 */
    @PostMapping("/{id}/poll")
    public ResponseEntity<Status> poll(@PathVariable String id) {
        var source = source(id);
        return ResponseEntity.accepted().body(service.get().trigger(source, false));
    }
    /** 乐观版本冲突需刷新页面，不自动覆盖另一位运营者的控制。 */
    @PostMapping("/{id}/pause")
    public Status pause(@PathVariable String id, @RequestBody Control control) {
        var source = source(id);
        return service.get().control(source, control.expectedRevision(), true);
    }
    /** 从原检查点继续，已成功记录不会再次计数。 */
    @PostMapping("/{id}/resume")
    public Status resume(@PathVariable String id, @RequestBody Control control) {
        var source = source(id);
        return service.get().control(source, control.expectedRevision(), false);
    }
    /** 只重试来源当前等待任务，不接受客户端指定其他任务ID。 */
    @PostMapping("/{id}/retry")
    public ResponseEntity<Status> retry(@PathVariable String id) {
        var source = source(id);
        return ResponseEntity.accepted().body(service.get().trigger(source, true));
    }
    /** 历史页查询有界，分页游标仅为运行序号。 */
    @GetMapping("/{id}/runs")
    public Runs runs(@PathVariable String id, @RequestParam(required = false) String before, @RequestParam(defaultValue = "20") int limit) {
        var source = source(id);
        return service.get().runs(source, before, limit);
    }
    /** UUID必须同时属于当前来源，跨来源查询与不存在均返回404。 */
    @GetMapping("/{id}/runs/{runId}")
    public Run run(@PathVariable String id, @PathVariable String runId) {
        var source = source(id);
        var run = service.get().run(source, runId);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return run;
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
        return source.tenantId().equals(actor.tenantId()) && (source.userId().equals(actor.userId()) || source.operatorUserIds().contains(actor.userId()));
    }
    /** 非法分页输入与内部故障区别处理。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_SYNC_REQUEST"));
    }
    /** 只提供稳定错误码，不返回内部堆栈或配置。 */
    @ExceptionHandler(SyncFailure.class)
    public ResponseEntity<Map<String, String>> failure(SyncFailure failure) {
        int status = switch (failure.code()) {
            case "CONTROL_CONFLICT", "SOURCE_PAUSED", "NO_RETRYABLE_JOB", "SOURCE_BUSY" -> 409;
            default -> 503;
        };
        return ResponseEntity.status(status).body(Map.of("code", failure.code()));
    }
}
