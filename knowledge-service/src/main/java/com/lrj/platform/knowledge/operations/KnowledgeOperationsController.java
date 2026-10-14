package com.lrj.platform.knowledge.operations;

import com.lrj.platform.knowledge.ingest.job.IngestionAuthorizationException;
import com.lrj.platform.knowledge.ingest.job.IngestionJobConflictException;
import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import static com.lrj.platform.knowledge.operations.KnowledgeOperations.*;

/** 运营入口区分本人反馈和租户管理；未配置持久化时明确503而非退回内存。 */
@RestController
@RequestMapping("/rag/operations")
public class KnowledgeOperationsController {
    private final ObjectProvider<KnowledgeOperationsService> operations;
    private final ObjectProvider<KnowledgePublicationService> publications;
    private final ObjectProvider<KnowledgeProbe> probes;
    private final InternalSecurityProperties security;
    public KnowledgeOperationsController(ObjectProvider<KnowledgeOperationsService> operations,
            ObjectProvider<KnowledgePublicationService> publications, ObjectProvider<KnowledgeProbe> probes,
            InternalSecurityProperties security) {
        this.operations = operations; this.publications = publications; this.probes = probes; this.security = security;
    }
    /** 身份完全来自认证上下文，反馈请求不能自行指定租户或用户。 */
    @PostMapping("/feedback")
    public Feedback feedback(@RequestBody FeedbackInput input) { return available(operations).feedback(TenantContext.current(), input); }
    /** 普通用户只读本人主动反馈，游标避免无界加载。 */
    @GetMapping("/feedback")
    public List<Feedback> myFeedback(@RequestParam(defaultValue = "~") String before, @RequestParam(defaultValue = "20") int limit) {
        return available(operations).feedbacks(TenantContext.current(), false, before, limit);
    }
    /** 租户收件箱由服务层校验ingest权限，不扩大普通反馈的读范围。 */
    @GetMapping("/feedback/inbox")
    public List<Feedback> inbox(@RequestParam(defaultValue = "~") String before, @RequestParam(defaultValue = "20") int limit) {
        return available(operations).feedbacks(TenantContext.current(), true, before, limit);
    }
    /** 来源反馈和创建幂等键经服务端核对，避免重试产生多个修订。 */
    @PostMapping("/candidates")
    public Candidate create(@RequestBody CandidateInput input) { return available(operations).create(TenantContext.current(), input); }
    /** 管理列表按认证租户与稳定游标读取。 */
    @GetMapping("/candidates")
    public List<Candidate> list(@RequestParam(defaultValue = "~") String before, @RequestParam(defaultValue = "20") int limit) {
        return available(operations).list(TenantContext.current(), before, limit);
    }
    /** 跨租户候选按不存在处理，避免泄露资源标识。 */
    @GetMapping("/candidates/{id}")
    public Candidate get(@PathVariable String id) { return available(operations).get(TenantContext.current(), id); }
    /** 反馈只作为同文档运营信号，不作为自动审核依据。 */
    @GetMapping("/candidates/{id}/feedback-summary")
    public Map<String, Long> feedbackSummary(@PathVariable String id) {
        return available(operations).feedbackSummary(TenantContext.current(), id);
    }
    /** 有界读取同租户操作记录，审计不包含正文副本。 */
    @GetMapping("/candidates/{id}/history")
    public List<Audit> history(@PathVariable String id, @RequestParam(defaultValue = "50") int limit) {
        return available(operations).history(TenantContext.current(), id, limit);
    }
    /** 所有编辑携带版本，旧页面不能直接覆盖新修订。 */
    @PutMapping("/candidates/{id}")
    public Candidate edit(@PathVariable String id, @RequestBody Edit command) {
        return available(operations).edit(TenantContext.current(), id, version(command.expectedVersion()), command.draft());
    }
    /** 检查由服务端执行，不接收客户端的通过标志。 */
    @PostMapping("/candidates/{id}/evaluate")
    public Candidate evaluate(@PathVariable String id, @RequestBody Version command) {
        return available(operations).evaluate(TenantContext.current(), id, version(command.expectedVersion()));
    }
    /** 审核仅能基于仍与当前内容匹配的检查证据。 */
    @PostMapping("/candidates/{id}/approve")
    public Candidate approve(@PathVariable String id, @RequestBody Version command) {
        return available(publications).approve(TenantContext.current(), id, version(command.expectedVersion()));
    }
    /** 202表示入库已提交，终态以返回的status及后续sync为准。 */
    @PostMapping("/candidates/{id}/publish")
    public ResponseEntity<Candidate> publish(@PathVariable String id, @RequestBody Version command) {
        return ResponseEntity.accepted().body(available(publications).publish(TenantContext.current(), id, version(command.expectedVersion())));
    }
    /** 显式同步权威job状态，保持GET无发布副作用。 */
    @PostMapping("/candidates/{id}/sync")
    public Candidate sync(@PathVariable String id, @RequestBody Version command) {
        return available(publications).sync(TenantContext.current(), id, version(command.expectedVersion()));
    }
    /** 原令牌仅用于本次内部检索，不进入运营持久化快照。 */
    @PostMapping("/candidates/{id}/probe")
    public Candidate probe(@PathVariable String id, @RequestBody Version command, HttpServletRequest request) {
        return available(publications).probe(TenantContext.current(), id, version(command.expectedVersion()),
                request.getHeader(security.getInternalHeader()), available(probes));
    }
    /** 回滚先生成新草稿，不能直接撤销已经发生的入库副作用。 */
    @PostMapping("/candidates/{id}/rollback-draft")
    public Candidate rollback(@PathVariable String id, @RequestBody Rollback command) {
        return available(publications).rollbackDraft(TenantContext.current(), id, command.idempotencyKey());
    }
    private static long version(Long value) {
        if (value == null || value < 0) throw new IllegalArgumentException("non-negative expectedVersion required");
        return value;
    }
    private static <T> T available(ObjectProvider<T> provider) {
        T value = provider.getIfAvailable();
        if (value == null) throw new OperationsException("unavailable", "persistent knowledge operations are not enabled");
        return value;
    }
    /** 将业务冲突映射为稳定协议，避免客户端依赖内部异常类型。 */
    @ExceptionHandler(OperationsException.class)
    public ResponseEntity<Map<String, String>> business(OperationsException error) {
        int status = switch (error.code()) { case "forbidden" -> 403; case "not_found" -> 404; case "unavailable" -> 503; default -> 409; };
        return ResponseEntity.status(status).body(Map.of("code", error.code(), "message", error.getMessage()));
    }
    /** 只回传字段约束信息，不把输入正文写入错误响应。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "bad_request", "message", error.getMessage()));
    }
    /** 复用文档授权结果并隐藏内部鉴权细节。 */
    @ExceptionHandler(IngestionAuthorizationException.class)
    public ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("code", "forbidden", "message", "document edit permission required"));
    }
    /** 入库并发冲突沿用运营409恢复协议。 */
    @ExceptionHandler(IngestionJobConflictException.class)
    public ResponseEntity<Map<String, String>> conflict() { return business(OperationsException.conflict()); }
    /** 存储/HTTP依赖异常不透出SQL、地址或凭据。 */
    @ExceptionHandler({org.springframework.dao.DataAccessException.class, org.springframework.web.client.RestClientException.class})
    public ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(503).body(Map.of("code", "unavailable", "message", "operation dependency unavailable; refresh before retry"));
    }
    public record Version(Long expectedVersion) {}
    public record Edit(Long expectedVersion, Draft draft) {}
    public record Rollback(String idempotencyKey) {}
}
