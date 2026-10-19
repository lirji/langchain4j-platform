package com.lrj.platform.knowledge.ingest.job;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.alibaba.ttl.TtlRunnable;
import com.lrj.platform.security.TenantContext;
import io.opentelemetry.context.Context;
import org.slf4j.MDC;
import java.util.Map;
import java.util.Set;

/** 入库池的唯一上下文包装：TTL 传递不可变业务快照，OTel scope 接续追踪，不传播事务资源。 */
final class IngestionExecutionContext {
    private static final TransmittableThreadLocal<Snapshot> CAPTURED = new TransmittableThreadLocal<>();
    private IngestionExecutionContext() { }

    /** 每次提交重新捕获，异常/线程复用/内联执行均恢复执行线程原状态。 */
    static Runnable wrap(Runnable task) {
        Snapshot previous = CAPTURED.get();
        CAPTURED.set(Snapshot.capture());
        try {
            return TtlRunnable.get(() -> {
                Snapshot context = CAPTURED.get(), before = Snapshot.capture();
                try (var scope = context.trace().makeCurrent()) {
                    context.restoreBusiness();
                    task.run();
                } finally { before.restoreBusiness(); }
            }, true);
        } finally {
            if (previous == null) CAPTURED.remove(); else CAPTURED.set(previous);
        }
    }

    private record Snapshot(TenantContext.Tenant tenant, Map<String,String> mdc, Context trace) {
        static Snapshot capture() {
            var raw = TenantContext.captureRaw();
            var tenant = raw == null ? null : new TenantContext.Tenant(raw.tenantId(),raw.userId(),
                    raw.scopes() == null ? Set.of() : Set.copyOf(raw.scopes()),raw.department());
            var mdc = MDC.getCopyOfContextMap();
            return new Snapshot(tenant, mdc == null ? Map.of() : Map.copyOf(mdc), Context.current());
        }
        void restoreBusiness() {
            if (tenant == null) TenantContext.clear(); else TenantContext.set(tenant);
            MDC.setContextMap(mdc);
        }
    }
}
