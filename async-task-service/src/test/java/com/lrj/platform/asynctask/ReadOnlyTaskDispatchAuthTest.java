package com.lrj.platform.asynctask;

import com.lrj.platform.protocol.asynctask.ReadOnlyTaskClaimRequest;
import com.lrj.platform.security.AsyncTaskWorkerToken;
import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.InternalTokenAuthFilter;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实签名与两层filter验证控制面purpose绑定; 数据库领取另有事务测试. */
class ReadOnlyTaskDispatchAuthTest {
    private final ReadOnlyTaskDispatch dispatch = mock(ReadOnlyTaskDispatch.class);
    private final ReadOnlyTaskDispatchController controller = new ReadOnlyTaskDispatchController(dispatch, "agentscope-platform");
    private final InternalSecurityProperties security = new InternalSecurityProperties();
    private final AsyncTaskWorkerToken worker = new AsyncTaskWorkerToken("test-dispatch-worker-key-with-32-characters",
            Duration.ofSeconds(60), Duration.ofSeconds(5), "platform-services", "async-task-worker", "async-task-worker-v1", "agentscope-platform");
    private final InternalToken internal = new InternalToken("test-dispatch-internal-key-with-32-characters", Duration.ofMinutes(5));

    private int invoke(String proof, String bodyWorker, boolean ordinary) throws Exception {
        var request = new MockHttpServletRequest("POST", "/async/tasks/dispatch/claim");
        request.addHeader(ordinary ? security.getInternalHeader() : security.getAsyncWorker().getHeader(), proof);
        var response = new MockHttpServletResponse();
        var outcome = new AtomicReference<ResponseEntity<?>>();
        new AsyncTaskWorkerAuthFilter(worker, security).doFilter(request, response, (r, s) ->
                new InternalTokenAuthFilter(internal, security, false).doFilter(r, s, (rr, ss) -> {
                    var principal = (AsyncTaskWorkerToken.Principal) request.getAttribute(AsyncTaskWorkerAuthFilter.PRINCIPAL_ATTRIBUTE);
                    outcome.set(controller.claim(new ReadOnlyTaskClaimRequest(bodyWorker), principal));
                }));
        assertThat(TenantContext.captureRaw()).isNull();
        return outcome.get() == null ? response.getStatus() : outcome.get().getStatusCode().value();
    }

    @Test void ordinaryUserAndDifferentActionsCannotClaimGlobalQueue() throws Exception {
        var owner = new TenantContext.Tenant("acme", "alice", Set.of("agent"));
        assertThat(invoke(internal.mint(owner), "agentscope-platform.one", true)).isEqualTo(401);
        assertThat(invoke(worker.mint(owner, "agentscope-platform.one", "lease", "readonly-dispatch"), "agentscope-platform.one", false)).isEqualTo(401);
        assertThat(invoke(worker.mint(owner, "agentscope-platform.one", "dispatch", "readonly-dispatch"), "agentscope-platform.one", false)).isEqualTo(403);
        verifyNoInteractions(dispatch);
    }

    @Test void bodyWorkerMustMatchSignedServiceWorkerAndContextClears() throws Exception {
        var control = new TenantContext.Tenant("_dispatch", "_dispatch", Set.of());
        String proof = worker.mint(control, "agentscope-platform.one", "dispatch", "readonly-dispatch");
        assertThat(invoke(proof, "agentscope-platform.two", false)).isEqualTo(403);
        assertThat(invoke(proof, "agentscope-platform.one", false)).isEqualTo(204);
        verify(dispatch).claim("agentscope-platform.one");
    }
}
