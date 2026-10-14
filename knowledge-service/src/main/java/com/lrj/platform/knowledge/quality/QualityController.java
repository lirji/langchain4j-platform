package com.lrj.platform.knowledge.quality;

import com.lrj.platform.knowledge.operations.OperationsException;
import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import static com.lrj.platform.protocol.knowledge.RetrievalQuality.*;

/** 复用知识运营路由，未配置关系持久化时明确503，身份与结果均不能由请求伪造。 */
@RestController
@RequestMapping("/rag/operations/quality")
public final class QualityController {
    private final ObjectProvider<QualityService> service;
    private final InternalSecurityProperties security;
    public QualityController(ObjectProvider<QualityService> service, InternalSecurityProperties security) { this.service = service; this.security = security; }
    /** 列表游标和页大小经过服务端校验。 */
    @GetMapping("/datasets")
    public List<Dataset> list(@RequestParam(defaultValue = "~") String before, @RequestParam(defaultValue = "20") int limit) { return service().list(TenantContext.current(), before, limit); }
    /** 创建意图以认证用户和幂等键隔离。 */
    @PostMapping("/datasets")
    public Dataset create(@RequestBody Create command) { return service().create(TenantContext.current(), command); }
    /** 详情不接受客户端提供的租户。 */
    @GetMapping("/datasets/{id}")
    public Dataset get(@PathVariable String id) { return service().get(TenantContext.current(), id); }
    /** 旧版本编辑不能覆盖新的标注或基线。 */
    @PutMapping("/datasets/{id}")
    public Dataset edit(@PathVariable String id, @RequestBody Edit command) { return service().edit(TenantContext.current(), id, command); }
    /** 基线引用服务端完整运行，不接收人工passed值。 */
    @PostMapping("/datasets/{id}/baseline")
    public Dataset baseline(@PathVariable String id, @RequestBody Baseline command) { return service().baseline(TenantContext.current(), id, command); }
    /** 有界历史列表用于响应丢失后的恢复。 */
    @GetMapping("/datasets/{id}/runs")
    public List<Run> runs(@PathVariable String id, @RequestParam(defaultValue = "~") String before, @RequestParam(defaultValue = "20") int limit) { return service().runs(TenantContext.current(), id, before, limit); }
    /** 原令牌只在本次请求内转发，不能落入运行记录。 */
    @PostMapping("/datasets/{id}/runs")
    public Run execute(@PathVariable String id, @RequestBody Execute command, HttpServletRequest request) {
        return service().execute(TenantContext.current(), id, command, request.getHeader(security.getInternalHeader()));
    }
    /** 自动化消费入口：回归422，无基线或运行未完成409，失败503，只有PASS返回200。 */
    @GetMapping("/runs/{id}/gate")
    public ResponseEntity<Run> gate(@PathVariable String id) {
        Run run = service().run(TenantContext.current(), id);
        int status = run.status() == Status.RUNNING ? 409 : switch (run.gate().verdict()) {
            case PASS -> 200; case REGRESSION -> 422; case BASELINE_REQUIRED -> 409; case FAILED -> 503;
        };
        return ResponseEntity.status(status).body(run);
    }
    /** GET仅观察状态，不隐式重跑评测。 */
    @GetMapping("/runs/{id}")
    public Run run(@PathVariable String id) { return service().run(TenantContext.current(), id); }
    /** 仅终止服务端已过期意图，不能取消有效在途请求。 */
    @PostMapping("/runs/{id}/abandon")
    public Run abandon(@PathVariable String id) { return service().abandon(TenantContext.current(), id); }
    private QualityService service() {
        QualityService value = service.getIfAvailable();
        if (value == null) throw new OperationsException("unavailable", "persistent quality evaluation is not enabled");
        return value;
    }
    /** 业务错误保持稳定状态码；不透传持久化和远程依赖细节。 */
    @ExceptionHandler(OperationsException.class)
    public ResponseEntity<Map<String,String>> business(OperationsException error) {
        int status = switch (error.code()) { case "forbidden" -> 403; case "not_found" -> 404; case "unavailable" -> 503; default -> 409; };
        return ResponseEntity.status(status).body(Map.of("code", error.code(), "message", error.getMessage()));
    }
    /** 只回传受控字段错误，不包含请求正文。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("code", "bad_request", "message", error.getMessage())); }
    /** 存储故障保持未知结果语义，允许通过运行历史核对。 */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String,String>> storage() { return ResponseEntity.status(503).body(Map.of("code", "unavailable", "message", "evaluation storage unavailable; refresh before retry")); }
}
