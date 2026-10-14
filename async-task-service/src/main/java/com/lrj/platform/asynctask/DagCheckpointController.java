package com.lrj.platform.asynctask;
import com.lrj.platform.protocol.asynctask.DagCheckpoint;
import com.lrj.platform.protocol.asynctask.DagCheckpointRequest;
import com.lrj.platform.security.AsyncTaskWorkerToken;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
/** 专用checkpoint action认证；不接受用户JWT或客户端自报租户。 */
@RestController
@ConditionalOnProperty(name="app.async-task.dispatch.enabled", havingValue="true")
public final class DagCheckpointController {
    private final DagCheckpointStore store;
    /** 注入中央检查点用例，HTTP层不访问持久化。 */
    public DagCheckpointController(DagCheckpointStore store) { this.store=store; }
    /** 带租约的读取或CAS写入，与普通任务状态接口分开演进。 */
    @PostMapping("/async/tasks/{id}/checkpoint")
    public DagCheckpoint access(@PathVariable String id, @RequestBody DagCheckpointRequest request,
            @RequestAttribute(AsyncTaskWorkerAuthFilter.PRINCIPAL_ATTRIBUTE) AsyncTaskWorkerToken.Principal actor) {
        return store.access(id, actor, request);
    }
}
