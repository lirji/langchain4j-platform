package com.lrj.platform.workflow;

import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.security.InternalTokenAuthFilter;
import com.lrj.platform.security.OutboundCallbackPolicy;
import com.lrj.platform.security.TenantContext;
import com.lrj.platform.observability.TraceIdFilter;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 退款审批工作流的业务封装：启流程 / 查待办 / 完成审批 / 查实例，挡在 Flowable
 * {@link RuntimeService} + {@link TaskService} + {@link HistoryService} 之上。
 *
 * <p><b>租户隔离（坑 3）</b>：每个流程实例都把发起人的 {@code tenantId} 写成流程变量。
 * 待办列表 / 完成审批 / 查实例都用 {@code processVariableValueEquals("tenantId", 当前租户)}
 * 过滤，租户 B 拿不到、也无法 complete 租户 A 的任务。<em>不</em>依赖 Flowable 原生 start-tenant
 * 查找——classpath 是 tenant-less 部署，带 tenant 启动需 fallback 配置，流程变量过滤更简单且同样严格。
 *
 * <p>v1 所有 ServiceTask 同步执行（async executor 关），故 {@code start()} 返回时流程要么已到
 * End（{@code COMPLETED}，reply 已生成），要么停在 UserTask（{@code WAITING_APPROVAL}，待人工）。
 */
@Service
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    /** 对应 refund-approval.bpmn20.xml 里 process 的 id。 */
    private static final String PROCESS_KEY = "refundApproval";
    private static final String REFUND_START_OPERATION = "refund_start";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_WAITING = "WAITING_APPROVAL";

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final AuditLogger audit;
    private final WorkflowProperties props;
    private final WorkflowReplyStore replyStore;
    private final WorkflowMetrics metrics;
    private final WorkflowOutbox outbox;
    private final WorkflowAsyncTaskNotifier asyncTaskNotifier;
    private final WorkflowTerminalEventOutbox terminalEventOutbox;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final OutboundCallbackPolicy callbackPolicy;
    private final WorkflowIdempotencyStore idempotencyStore;
    private final TransactionTemplate transaction;

    public WorkflowService(RuntimeService workflowRuntimeService,
                           TaskService workflowTaskService,
                           HistoryService workflowHistoryService,
                           AuditLogger audit,
                           WorkflowProperties props,
                           WorkflowReplyStore replyStore,
                           WorkflowMetrics metrics,
                           WorkflowOutbox outbox,
                           WorkflowAsyncTaskNotifier asyncTaskNotifier,
                           WorkflowTerminalEventOutbox terminalEventOutbox,
                           org.springframework.context.ApplicationEventPublisher events,
                           OutboundCallbackPolicy callbackPolicy,
                           WorkflowIdempotencyStore idempotencyStore,
                           @Qualifier("workflowTransactionManager") PlatformTransactionManager transactionManager) {
        this.runtimeService = workflowRuntimeService;
        this.taskService = workflowTaskService;
        this.historyService = workflowHistoryService;
        this.audit = audit;
        this.props = props;
        this.replyStore = replyStore;
        this.metrics = metrics;
        this.outbox = outbox;
        this.asyncTaskNotifier = asyncTaskNotifier;
        this.terminalEventOutbox = terminalEventOutbox;
        this.events = events;
        this.callbackPolicy = callbackPolicy;
        this.idempotencyStore = idempotencyStore;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setName("workflow-refund-start");
    }

    /**
     * 终态处理收口（#8 outbox 入队 + 渠道回推事件）。三个终态点（自动受理 / 人工 complete / 超时驳回）
     * 各调一次。{@code chatId} 缺省从流程变量读。
     */
    private void onTerminal(String instanceId, String tenantId, String chatId, String outcome) {
        String cid = chatId != null ? chatId : str(readVariable(instanceId, "chatId"));
        String reply = replyStore.find(instanceId);
        if (!useKafkaNotification()) {
            enqueuePush(instanceId, tenantId);
        }
        // kafka 档：终态事件 outbox 行已由 WorkflowTerminalOutboxListener 在 Flowable 终态事务内原子写入，
        // 由 WorkflowTerminalEventRelay relay 到 Kafka；此处不再做提交后直发（那会在崩溃时丢事件、无兜底记录）。
        // 渠道回推由 channel-service 的 WorkflowTerminalKafkaListener 消费完成。
        events.publishEvent(new WorkflowTerminalEvent(instanceId, tenantId, cid, outcome, reply));
    }

    /**
     * 发起退款流程。低风险自动受理（直接 COMPLETED），高风险挂起等审批（WAITING_APPROVAL）。
     *
     * <p><b>强幂等</b>：传了 {@code dedupeId}（渠道消息 id）时，先在 {@code WF_IDEMPOTENCY} 以数据库
     * 复合主键竞争 claim；claim、Flowable 创建和 instance 绑定由同一个事务提交。相同键与相同请求返回
     * 原实例（{@code deduplicated=true}），相同键配不同用户/参数返回 409，创建失败则 claim 一起回滚。
     * 不传 dedupeId 时仍用随机 businessKey，仅用于追溯，不会误合并两次合法的相同请求。
     */
    public StartResult start(String chatId, String message, String dedupeId, String webhookUrl) {
        TenantContext.Tenant t = TenantContext.current();
        String safeWebhookUrl = null;
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            try {
                safeWebhookUrl = callbackPolicy.requireAllowed(webhookUrl).toString();
            } catch (OutboundCallbackPolicy.UnsafeCallbackException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "webhookUrl is not allowed");
            }
        }
        String cid = chatId == null ? "default" : chatId;
        String normalizedDedupeId = normalizeDedupeId(dedupeId);
        String normalizedMessage = message == null ? "" : message;
        String validatedWebhookUrl = safeWebhookUrl;
        try {
            return Objects.requireNonNull(transaction.execute(status -> startAtomically(
                    t, cid, normalizedMessage, normalizedDedupeId, validatedWebhookUrl)));
        } catch (WorkflowIdempotencyStore.IdempotencyConflictException conflict) {
            log.info("workflow start idempotency conflict: tenantId={} operation={} reason={}",
                    t.tenantId(), REFUND_START_OPERATION, conflict.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "idempotency key conflicts with another refund request");
        }
    }

    private StartResult startAtomically(TenantContext.Tenant t,
                                        String cid,
                                        String message,
                                        String dedupeId,
                                        String safeWebhookUrl) {
        String businessKey = buildBusinessKey(t.tenantId(), cid, dedupeId);
        String keyHash = null;
        String requestHash = null;

        if (dedupeId != null) {
            keyHash = sha256(cid, dedupeId);
            requestHash = sha256(REFUND_START_OPERATION, t.tenantId(), t.userId(), cid, message,
                    safeWebhookUrl == null ? "" : safeWebhookUrl);
            WorkflowIdempotencyStore.Claim claim = idempotencyStore.claim(
                    t.tenantId(), REFUND_START_OPERATION, keyHash, requestHash, businessKey);
            if (!claim.acquired()) {
                log.info("workflow start deduplicated: businessKey={} instanceId={}",
                        businessKey, claim.instanceId());
                return describeExisting(claim.instanceId(), true);
            }

            // Expand 上线兼容：升级前已经由旧版 businessKey 创建的实例先收编到账本，避免发布瞬间重放。
            String legacyInstanceId = findExistingInstanceId(t.tenantId(), businessKey);
            if (legacyInstanceId != null) {
                if (!legacyRequestMatches(legacyInstanceId, t.userId(), cid, message, safeWebhookUrl)) {
                    throw new WorkflowIdempotencyStore.IdempotencyConflictException(
                            "legacy workflow business key is bound to another request");
                }
                idempotencyStore.attachInstance(t.tenantId(), REFUND_START_OPERATION, keyHash, requestHash,
                        legacyInstanceId);
                log.info("workflow legacy instance adopted by idempotency ledger: businessKey={} instanceId={}",
                        businessKey, legacyInstanceId);
                return describeExisting(legacyInstanceId, true);
            }
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("tenantId", t.tenantId());
        vars.put("userId", t.userId());
        vars.put("chatId", cid);
        vars.put("message", message);
        // 终态结果默认 auto（自动受理时的取值）；人工 complete / 超时驳回会在 complete 时覆盖。
        // 供 WorkflowTerminalOutboxListener 在 end 事件读取，写进终态事件 outbox（kafka 档）。
        vars.put("terminalOutcome", "auto");
        // #8：发起方传了回推地址就存成流程变量，终态时入 outbox 可靠投递
        if (safeWebhookUrl != null) {
            vars.put("webhookUrl", safeWebhookUrl);
        }
        // 把当前请求的 traceId 存成流程变量，超时 sweep 时取回放进 MDC → 日志跨事件串联（计划 2.5）
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId != null && !traceId.isBlank()) {
            vars.put("startTraceId", traceId);
        }

        ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY, businessKey, vars);
        String instanceId = pi.getId();
        if (dedupeId != null) {
            idempotencyStore.attachInstance(t.tenantId(), REFUND_START_OPERATION, keyHash, requestHash, instanceId);
        }
        String priority = str(readVariable(instanceId, "priority"));
        audit.record(AuditEventType.WORKFLOW_STARTED, Map.of(
                "instanceId", instanceId, "chatId", cid, "priority", nz(priority)));
        metrics.recordStarted(priority);

        if (pi.isEnded()) {
            audit.record(AuditEventType.WORKFLOW_COMPLETED, Map.of("instanceId", instanceId, "approval", "auto"));
            metrics.recordCompleted("auto");
            onTerminal(instanceId, t.tenantId(), cid, "auto");
            return new StartResult(instanceId, STATUS_COMPLETED, replyStore.find(instanceId), null, priority, false);
        }

        Task task = taskService.createTaskQuery().processInstanceId(instanceId).active().singleResult();
        String taskId = task == null ? null : task.getId();
        audit.record(AuditEventType.APPROVAL_REQUESTED, Map.of(
                "instanceId", instanceId, "taskId", nz(taskId), "priority", nz(priority)));
        return new StartResult(instanceId, STATUS_WAITING, null, taskId, priority, false);
    }

    private String findExistingInstanceId(String tenantId, String businessKey) {
        List<HistoricProcessInstance> existing = historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey)
                .variableValueEquals("tenantId", tenantId)
                .orderByProcessInstanceStartTime().asc()
                .listPage(0, 1);
        return existing.isEmpty() ? null : existing.get(0).getId();
    }

    private boolean legacyRequestMatches(String instanceId,
                                         String userId,
                                         String chatId,
                                         String message,
                                         String webhookUrl) {
        return Objects.equals(nz(str(readVariable(instanceId, "userId"))), nz(userId))
                && Objects.equals(nz(str(readVariable(instanceId, "chatId"))), nz(chatId))
                && Objects.equals(nz(str(readVariable(instanceId, "message"))), nz(message))
                && Objects.equals(nz(str(readVariable(instanceId, "webhookUrl"))), nz(webhookUrl));
    }

    /**
     * #8 终态入队：实例若带 {@code webhookUrl} 流程变量，把"待投递"落 {@link WorkflowOutbox}，
     * 由 {@code WorkflowOutboxDispatcher} 可靠重投。无 webhookUrl 则不入队（客户端轮询 status 端点，行为同旧版）。
     * best-effort：入队失败只告警不影响主流程（reply 已持久，客户端仍可轮询）。
     */
    private void enqueuePush(String instanceId, String tenantId) {
        String url = str(readVariable(instanceId, "webhookUrl"));
        if (url == null || url.isBlank()) {
            return;
        }
        if (useAsyncTaskNotification()) {
            boolean published = asyncTaskNotifier.publishTerminal(instanceId, tenantId, url, replyStore.find(instanceId));
            if (published) {
                return;
            }
            if (!props.getTerminalNotification().isFallbackToLocalOutbox()) {
                return;
            }
            log.warn("workflow terminal async-task notification failed; falling back to local outbox instanceId={}", instanceId);
        }
        try {
            outbox.enqueue(instanceId, tenantId, url, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("outbox enqueue 失败 instanceId={}：{}", instanceId, e.toString());
        }
    }

    private boolean useAsyncTaskNotification() {
        return isAsyncTaskMode(props.getTerminalNotification().getMode());
    }

    private boolean useKafkaNotification() {
        return isKafkaMode(props.getTerminalNotification().getMode());
    }

    /** 终态通知模式判定（纯函数，便于单测）。 */
    static boolean isAsyncTaskMode(String mode) {
        return "async-task".equalsIgnoreCase(mode) || "async_task".equalsIgnoreCase(mode);
    }

    static boolean isKafkaMode(String mode) {
        return "kafka".equalsIgnoreCase(mode);
    }

    /**
     * 构造流程 businessKey。传了 {@code dedupeId} → 稳定可去重的 {@code tenant:chatId:dedupeId}；
     * 否则随机 UUID 后缀（仅追溯、不去重）。抽成 static 纯函数便于单测。
     */
    static String buildBusinessKey(String tenantId, String chatId, String dedupeId) {
        if (dedupeId != null && !dedupeId.isBlank()) {
            return tenantId + ":" + chatId + ":" + dedupeId.trim();
        }
        return tenantId + ":" + chatId + ":" + UUID.randomUUID();
    }

    /** 与 AgentScope 入口相同的有限 opaque key 语法；Java 直调也不能绕过。 */
    static String normalizeDedupeId(String dedupeId) {
        if (dedupeId == null || dedupeId.isBlank()) {
            return null;
        }
        String normalized = dedupeId.trim();
        if (!IDEMPOTENCY_KEY.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid dedupeId");
        }
        return normalized;
    }

    /** 长度前缀编码后做 SHA-256，避免拼接分隔符歧义；只持久化摘要，不把用户正文写入幂等表。 */
    static String sha256(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = (part == null ? "" : part).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** 把一个既有实例（去重命中时）描述成 StartResult：已结束回 COMPLETED+reply，在跑回 WAITING+taskId。 */
    private StartResult describeExisting(String instanceId, boolean deduplicated) {
        boolean running = runtimeService.createProcessInstanceQuery()
                .processInstanceId(instanceId).singleResult() != null;
        String priority = str(readVariable(instanceId, "priority"));
        if (!running) {
            return new StartResult(instanceId, STATUS_COMPLETED, replyStore.find(instanceId), null, priority, deduplicated);
        }
        Task task = taskService.createTaskQuery().processInstanceId(instanceId).active().singleResult();
        String taskId = task == null ? null : task.getId();
        return new StartResult(instanceId, STATUS_WAITING, null, taskId, priority, deduplicated);
    }

    /** 本租户待审 UserTask 列表。 */
    public List<TaskView> listTasks() {
        String tenant = TenantContext.current().tenantId();
        List<Task> tasks = taskService.createTaskQuery()
                .processVariableValueEquals("tenantId", tenant)
                .active()
                .orderByTaskCreateTime().desc()
                .list();
        List<TaskView> views = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            String instanceId = task.getProcessInstanceId();
            views.add(new TaskView(
                    task.getId(),
                    task.getName(),
                    instanceId,
                    str(readVariable(instanceId, "priority")),
                    str(readVariable(instanceId, "summary")),
                    task.getAssignee()));
        }
        return views;
    }

    /**
     * 认领任务（#7 任务分配粒度）：把任务 assignee 设为当前用户，避免多人审同一单。
     * 已被他人认领 → 409（友好冲突，不是 500）。返回认领后的任务视图。
     */
    public TaskView claim(String taskId) {
        TenantContext.Tenant t = TenantContext.current();
        Task task = activeTenantTask(taskId, t.tenantId());
        String assignee = task.getAssignee();
        if (assignee != null && !assignee.isBlank() && !assignee.equals(t.userId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task already claimed by " + assignee);
        }
        try {
            taskService.claim(taskId, t.userId());
        } catch (FlowableObjectNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task already handled: " + taskId);
        }
        String instanceId = task.getProcessInstanceId();
        return new TaskView(taskId, task.getName(), instanceId,
                str(readVariable(instanceId, "priority")), str(readVariable(instanceId, "summary")), t.userId());
    }

    /** 取消认领（#7）：把任务放回待领池，供同租户其他 approver 接手。 */
    public void unclaim(String taskId) {
        TenantContext.Tenant t = TenantContext.current();
        activeTenantTask(taskId, t.tenantId()); // 租户校验 + 存在性
        try {
            taskService.unclaim(taskId);
        } catch (FlowableObjectNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task already handled: " + taskId);
        }
    }

    /** 取本租户下、仍 active 的任务；不存在/已处理/跨租户一律 404（不泄露跨租户任务存在）。 */
    private Task activeTenantTask(String taskId, String tenant) {
        Task task = taskService.createTaskQuery()
                .taskId(taskId)
                .processVariableValueEquals("tenantId", tenant)
                .active()
                .singleResult();
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found: " + taskId);
        }
        return task;
    }

    /** 完成审批：approved=true 走 resolve，false 走 reject（均同步执行），返回最终 reply。 */
    public CompleteResult complete(String taskId, boolean approved, String comment) {
        String tenant = TenantContext.current().tenantId();
        Task task = activeTenantTask(taskId, tenant);
        String instanceId = task.getProcessInstanceId();
        long approvalMs = approvalDurationMs(task);

        Map<String, Object> vars = new HashMap<>();
        vars.put("approved", approved);
        vars.put("comment", comment == null ? "" : comment);
        // 覆盖 start 时的默认 auto：人工审批 → granted/rejected。终态事务内可见于 end 监听器。
        vars.put("terminalOutcome", approved ? "granted" : "rejected");
        try {
            taskService.complete(taskId, vars);
        } catch (FlowableObjectNotFoundException e) {
            // #7 并发双重审批：预检与 complete 之间另一审批人/超时 sweeper 已处理掉 → 友好 409，不是 500
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task already handled by another approver: " + taskId);
        }

        audit.record(approved ? AuditEventType.APPROVAL_GRANTED : AuditEventType.APPROVAL_REJECTED,
                Map.of("instanceId", instanceId, "taskId", taskId));
        audit.record(AuditEventType.WORKFLOW_COMPLETED, Map.of(
                "instanceId", instanceId, "approval", approved ? "granted" : "rejected"));
        metrics.recordApprovalDuration(approvalMs);
        metrics.recordCompleted(approved ? "granted" : "rejected");
        onTerminal(instanceId, tenant, null, approved ? "granted" : "rejected");
        log.info("workflow complete: instanceId={} approved={}", instanceId, approved);

        return new CompleteResult(instanceId, STATUS_COMPLETED, replyStore.find(instanceId), approved);
    }

    /** UserTask 创建到现在的耗时（毫秒），创建时间缺失记 0。 */
    private static long approvalDurationMs(Task task) {
        return task.getCreateTime() == null ? 0L : System.currentTimeMillis() - task.getCreateTime().getTime();
    }

    /**
     * 审批超时自动驳回（#1）。由 {@link ApprovalTimeoutSweeper} 在 Spring 调度线程调用——该线程不过
     * 过滤器链，{@link TenantContext} 和日志 MDC 都是空的。故这里：
     * <ul>
     *   <li>从流程变量 {@code tenantId} 重建 {@link TenantContext}（审计归属正确）；</li>
     *   <li>铺 MDC 三件套（{@code traceId}/{@code tenantId}/{@code userId}），日志才不是 {@code [-] [-/-]}。
     *       traceId 优先取流程变量 {@code startTraceId}（start 时存的请求 traceId）→ 一个 id 串起
     *       「start」与「24h 后的超时驳回」；缺失则现造 8 位（仅内部串联）。</li>
     * </ul>
     * 走既有 reject 路径（{@code approved=false}），reply 由 {@code ServiceTaskDelegates.rejectionMessage} 生成。
     *
     * <p><b>与人工 complete 的竞态</b>：若超时同时审批人正点 complete，先到者赢；后到的 task 已不 active，
     * {@code active().singleResult()==null} 直接跳过（这是 #7「并发 complete 500」的局部预防）。
     */
    public void expireTask(String taskId) {
        Task task = taskService.createTaskQuery().taskId(taskId).active().singleResult();
        if (task == null) {
            return; // 已被人工处理 / 不存在 → 幂等跳过
        }
        String instanceId = task.getProcessInstanceId();
        String tenantId = str(readVariable(instanceId, "tenantId"));
        String startTraceId = str(readVariable(instanceId, "startTraceId"));
        long approvalMs = approvalDurationMs(task);

        TenantContext.Tenant prevTenant = TenantContext.captureRaw();
        String prevTrace = MDC.get(TraceIdFilter.MDC_KEY);
        String prevTenantMdc = MDC.get(InternalTokenAuthFilter.MDC_TENANT);
        String prevUserMdc = MDC.get(InternalTokenAuthFilter.MDC_USER);
        try {
            if (tenantId != null && !tenantId.isBlank()) {
                TenantContext.set(new TenantContext.Tenant(tenantId, "system-timeout", Set.of()));
            }
            MDC.put(TraceIdFilter.MDC_KEY,
                    (startTraceId != null && !startTraceId.isBlank())
                            ? startTraceId
                            : UUID.randomUUID().toString().substring(0, 8));
            if (tenantId != null) MDC.put(InternalTokenAuthFilter.MDC_TENANT, tenantId);
            MDC.put(InternalTokenAuthFilter.MDC_USER, "system-timeout");

            Map<String, Object> vars = new HashMap<>();
            vars.put("approved", false);
            vars.put("comment", "审批超时自动驳回（超过 " + props.getApprovalTimeout() + " 无人处理）");
            // 覆盖默认：超时驳回 → timeout（reply 由 reject 服务任务生成，终态事务内可见于 end 监听器）。
            vars.put("terminalOutcome", "timeout");
            try {
                taskService.complete(taskId, vars); // → reject ServiceTask 同步跑，写超时驳回 reply
            } catch (FlowableObjectNotFoundException e) {
                // 与人工 complete 竞态：审批人在预检后抢先完成 → 已无此 task，幂等跳过
                log.info("workflow expire skipped (already handled): taskId={}", taskId);
                return;
            }

            audit.record(AuditEventType.APPROVAL_TIMEOUT, Map.of("instanceId", instanceId, "taskId", taskId));
            audit.record(AuditEventType.WORKFLOW_COMPLETED, Map.of("instanceId", instanceId, "approval", "timeout"));
            metrics.recordTimeout();
            metrics.recordApprovalDuration(approvalMs);
            metrics.recordCompleted("timeout");
            onTerminal(instanceId, tenantId, null, "timeout");
            log.info("workflow approval timeout auto-rejected: instanceId={} taskId={}", instanceId, taskId);
        } finally {
            if (prevTenant != null) TenantContext.set(prevTenant); else TenantContext.clear();
            restoreMdc(TraceIdFilter.MDC_KEY, prevTrace);
            restoreMdc(InternalTokenAuthFilter.MDC_TENANT, prevTenantMdc);
            restoreMdc(InternalTokenAuthFilter.MDC_USER, prevUserMdc);
        }
    }

    private static void restoreMdc(String key, String prev) {
        if (prev != null) MDC.put(key, prev); else MDC.remove(key);
    }

    /** 查实例状态 + reply。跨租户访问按 404 处理。 */
    public InstanceView getInstance(String instanceId) {
        String owner = str(readVariable(instanceId, "tenantId"));
        String tenant = TenantContext.current().tenantId();
        if (owner == null || !owner.equals(tenant)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found: " + instanceId);
        }
        boolean running = runtimeService.createProcessInstanceQuery()
                .processInstanceId(instanceId).singleResult() != null;
        String status = running ? STATUS_WAITING : STATUS_COMPLETED;
        return new InstanceView(instanceId, status, replyStore.find(instanceId));
    }

    /**
     * PII 合规删除（#10）：清除本租户某 {@code chatId} 下的所有工作流持久化数据——运行中实例（强制删）、
     * 历史实例（{@code ACT_HI_*}）、{@code WF_REPLY}、{@code WF_OUTBOX}、{@code WF_IDEMPOTENCY}。
     * 覆盖个保法"删除我的数据"诉求；
     * {@code message}/{@code summary}/{@code reply} 这些可能含 PII 的字段一并清掉。
     *
     * <p>按流程变量 {@code tenantId}+{@code chatId} 定位，跨租户删不到（租户隔离同 {@link #listTasks}）。
     * 返回删除的实例数。审计 {@link AuditEventType#WORKFLOW_DATA_PURGED}。
     */
    public int purge(String chatId) {
        String tenant = TenantContext.current().tenantId();
        return Objects.requireNonNull(transaction.execute(status -> purgeAtomically(tenant, chatId)));
    }

    private int purgeAtomically(String tenant, String chatId) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();

        List<ProcessInstance> running = runtimeService.createProcessInstanceQuery()
                .variableValueEquals("tenantId", tenant)
                .variableValueEquals("chatId", chatId)
                .list();
        List<HistoricProcessInstance> historic = historyService.createHistoricProcessInstanceQuery()
                .variableValueEquals("tenantId", tenant)
                .variableValueEquals("chatId", chatId)
                .list();
        for (ProcessInstance pi : running) {
            ids.add(pi.getId());
        }
        for (HistoricProcessInstance hi : historic) {
            ids.add(hi.getId());
        }

        // Flowable 与业务表共用 workflowTransactionManager：任一删除失败都会整体回滚，避免账本悬挂。
        for (ProcessInstance pi : running) {
            runtimeService.deleteProcessInstance(pi.getId(), "PII purge");
        }
        for (HistoricProcessInstance hi : historic) {
            historyService.deleteHistoricProcessInstance(hi.getId());
        }
        for (String id : ids) {
            replyStore.delete(id);
            outbox.delete(id);
            terminalEventOutbox.delete(id);
            idempotencyStore.deleteByInstance(id);
        }
        audit.record(AuditEventType.WORKFLOW_DATA_PURGED, Map.of(
                "chatId", nz(chatId), "instances", ids.size()));
        log.info("workflow purge: tenant={} chatId={} 清除实例数={}", tenant, chatId, ids.size());
        return ids.size();
    }

    /**
     * 读流程变量：实例还在跑就从 runtime 取；已结束则从 history 取（默认 history level=audit
     * 已记录变量）。COMPLETED 实例的 priority / summary 仍能回读。
     *
     * <p>注意 {@code reply} <b>不</b>走这里——它是长文本，已挪到 {@link WorkflowReplyStore}（#5），
     * 由 {@code replyStore.find(instanceId)} 取回，不再灌 {@code ACT_HI_VARINST}。
     */
    private Object readVariable(String instanceId, String name) {
        boolean running = runtimeService.createProcessInstanceQuery()
                .processInstanceId(instanceId).singleResult() != null;
        if (running) {
            return runtimeService.getVariable(instanceId, name);
        }
        HistoricVariableInstance v = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(instanceId).variableName(name).singleResult();
        return v == null ? null : v.getValue();
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }
    private static String nz(String v) { return v == null ? "" : v; }

    public record StartResult(String instanceId, String status, String reply, String taskId, String priority,
                              boolean deduplicated) {}

    public record TaskView(String taskId, String name, String instanceId, String priority, String summary,
                           String assignee) {}

    public record CompleteResult(String instanceId, String status, String reply, boolean approved) {}

    public record InstanceView(String instanceId, String status, String reply) {}
}
