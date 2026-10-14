package com.lrj.platform.asynctask;

import com.lrj.platform.security.AsyncTaskWorkerToken;
import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证真实JWT的用途/任务绑定，中央行级身份隔离由数据库测试覆盖。 */
class DagCheckpointAuthTest {
    @Test void onlyCheckpointActionForThisTaskReachesTheController() throws Exception {
        var security = new InternalSecurityProperties();
        var worker = new AsyncTaskWorkerToken("test-checkpoint-worker-secret-32-characters", Duration.ofSeconds(60),
            Duration.ZERO, "platform-services", "async-task-worker", "async-task-worker-v1", "agentscope-platform");
        var identity = new TenantContext.Tenant("acme", "alice", Set.of("agent"));
        var user = new InternalToken("test-checkpoint-internal-secret-32-characters", Duration.ofMinutes(5));
        String[] proofs = {user.mint(identity), worker.mint(identity, "agentscope-platform.one", "event", "task"),
            worker.mint(identity, "agentscope-platform.one", "checkpoint", "other"),
            worker.mint(identity, "agentscope-platform.one", "checkpoint", "task")};
        for (int i=0; i<proofs.length; i++) {
            var request = new MockHttpServletRequest("POST", "/async/tasks/task/checkpoint");
            request.addHeader(security.getAsyncWorker().getHeader(), proofs[i]);
            var response = new MockHttpServletResponse();
            var reached = new AtomicBoolean();
            new AsyncTaskWorkerAuthFilter(worker, security).doFilter(request, response, (r,s) -> reached.set(true));
            assertThat(reached.get()).isEqualTo(i==3);
            assertThat(response.getStatus()).isEqualTo(i==3 ? 200 : 401);
        }
    }
}
