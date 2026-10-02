package com.lrj.platform.asynctask;

import com.lrj.platform.protocol.asynctask.ReadOnlyTaskClaimRequest;
import com.lrj.platform.security.AsyncTaskWorkerToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 只读调度控制面. 普通用户令牌和其他任务action不能领取全局队列. */
@RestController
@ConditionalOnProperty(name = "app.async-task.dispatch.enabled", havingValue = "true")
public final class ReadOnlyTaskDispatchController {
    private final ReadOnlyTaskDispatch dispatch;
    private final String serviceId;

    public ReadOnlyTaskDispatchController(ReadOnlyTaskDispatch dispatch,
            @Value("${app.async-task.dispatch.service-id:agentscope-platform}") String serviceId) {
        this.dispatch = dispatch;
        this.serviceId = serviceId;
    }

    /** JWT绑定的调度服务身份与body worker必须一致, 不接受tenant header来构造上下文. */
    @PostMapping("/async/tasks/dispatch/claim")
    public ResponseEntity<?> claim(@RequestBody ReadOnlyTaskClaimRequest request,
            @RequestAttribute(AsyncTaskWorkerAuthFilter.PRINCIPAL_ATTRIBUTE) AsyncTaskWorkerToken.Principal principal) {
        if (request == null || !serviceId.equals(principal.serviceId())
                || !"_dispatch".equals(principal.tenantId()) || !"_dispatch".equals(principal.actorUserId())
                || !principal.workerId().equals(request.workerId()))
            return ResponseEntity.status(403).build();
        var result = dispatch.claim(request.workerId());
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(result);
    }
}
