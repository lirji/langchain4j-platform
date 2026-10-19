package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.security.TenantContext;
import com.lrj.platform.observability.TraceIdFilter;
import org.slf4j.MDC;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 混合慢/快任务、饱和、关闭与上下文恢复；不以纯吞吐数字代替隔离断言。 */
class IngestionWorkerLoopTest {
    @AfterEach void clear() { TenantContext.clear(); MDC.clear(); }

    @Test void slowTenantDoesNotBlockSmallTaskAndSaturationLeavesJobsDurable() throws Exception {
        var store=new InMemoryIngestionJobStore();
        store.createOrGet(IngestionAdmissionTest.job("a-big","one"));
        store.createOrGet(IngestionAdmissionTest.job("a-big","two"));
        store.createOrGet(IngestionAdmissionTest.job("b-small","one"));
        var bigEntered=new CountDownLatch(1); var releaseBig=new CountDownLatch(1);
        var limits=new IngestionJobProperties();limits.setWorkerConcurrency(2);limits.setGlobalConcurrency(2);
        var clock=Clock.systemUTC();
        try(var worker=new IngestionJobWorker(store,job->{
            assertThat(TenantContext.current().tenantId()).isEqualTo(job.tenantId());
            assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isEqualTo(job.traceId());
            if(job.tenantId().equals("a-big")) { bigEntered.countDown();releaseBig.await(5,TimeUnit.SECONDS); }
            return PreparedIngestionFixtures.prepared(job);
        },(job,document,sink)->{
            assertThat(TenantContext.current().userId()).isEqualTo(job.userId());
        },new NoopIngestionTaskLifecycle(),clock,limits);
            var loop=new IngestionWorkerLoop(store,worker,new IngestionReconciler(store,clock,limits),10,limits)) {
            loop.poll();assertThat(bigEntered.await(3,TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(store.find("b-small","one").orElseThrow().status()).isEqualTo(IngestionStatus.READY));
            for(int i=0;i<10;i++) loop.poll();
            assertThat(store.find("a-big","two").orElseThrow().status()).isEqualTo(IngestionStatus.RECEIVED);
            assertThat(store.find("a-big","one").orElseThrow().status()).isEqualTo(IngestionStatus.PROCESSING);
            releaseBig.countDown();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(store.find("a-big","one").orElseThrow().status()).isEqualTo(IngestionStatus.READY));
        } finally { releaseBig.countDown(); }
    }

    @Test void closeIsBoundedStopsNewClaimsAndKeepsInterruptedWorkRecoverable() throws Exception {
        var store=new InMemoryIngestionJobStore();store.createOrGet(IngestionAdmissionTest.job("a","one"));store.createOrGet(IngestionAdmissionTest.job("b","two"));
        var started=new CountDownLatch(1);var hold=new CountDownLatch(1);
        var limits=new IngestionJobProperties();limits.setWorkerConcurrency(1);limits.setWorkerShutdownTimeout(Duration.ofMillis(50));
        var clock=Clock.systemUTC();
        try(var worker=new IngestionJobWorker(store,job->{started.countDown();hold.await();return PreparedIngestionFixtures.prepared(job);},
                (j,p,s)->{},new NoopIngestionTaskLifecycle(),clock,limits)) {
            var loop=new IngestionWorkerLoop(store,worker,new IngestionReconciler(store,clock,limits),10,limits);
            try {
                loop.poll();assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();
                long before=System.nanoTime();loop.close();
                assertThat(Duration.ofNanos(System.nanoTime()-before)).isLessThan(Duration.ofSeconds(2));
                loop.poll();assertThat(store.find("b","two").orElseThrow().status()).isEqualTo(IngestionStatus.RECEIVED);
                assertThat(store.find("a","one").orElseThrow().status()).isNotEqualTo(IngestionStatus.READY);
            } finally { hold.countDown();loop.close(); }
        }
    }

    @Test void ttlCapturesAtSubmissionAndRestoresTenantMdcAndOtelOnException() throws Exception {
        var key=io.opentelemetry.context.ContextKey.<String>named("test-ingestion-trace");
        try(var executor=Executors.newSingleThreadExecutor(Thread.ofPlatform().inheritInheritableThreadLocals(false).factory())) {
            executor.submit(()->{TenantContext.set(new TenantContext.Tenant("worker-before","user",Set.of()));MDC.put("extra","worker-before");}).get();
            TenantContext.set(new TenantContext.Tenant("caller-a","a",Set.of("ingest")));MDC.put("extra","caller-a");
            Runnable task;
            try(var scope=io.opentelemetry.context.Context.root().with(key,"trace-a").makeCurrent()) {
                task=IngestionExecutionContext.wrap(()-> {
                    assertThat(TenantContext.current().tenantId()).isEqualTo("caller-a");assertThat(MDC.get("extra")).isEqualTo("caller-a");
                    assertThat(io.opentelemetry.context.Context.current().get(key)).isEqualTo("trace-a");
                    TenantContext.set(new TenantContext.Tenant("dirty","dirty",Set.of()));MDC.clear();throw new IllegalStateException("test");
                });
            }
            TenantContext.set(new TenantContext.Tenant("caller-b","b",Set.of()));MDC.put("extra","caller-b");
            var future=executor.submit(task);assertThatThrownBy(future::get).hasCauseInstanceOf(IllegalStateException.class);
            executor.submit(()-> {
                assertThat(TenantContext.current().tenantId()).isEqualTo("worker-before");assertThat(MDC.get("extra")).isEqualTo("worker-before");
                assertThat(io.opentelemetry.context.Context.current().get(key)).isNull();
            }).get();
            assertThat(TenantContext.current().tenantId()).isEqualTo("caller-b");
            // 嵌套内联执行也应恢复外层，不能使用无条件 clear 破坏提交线程。
            IngestionExecutionContext.wrap(()-> IngestionExecutionContext.wrap(()-> TenantContext.clear()).run()).run();
            assertThat(TenantContext.current().tenantId()).isEqualTo("caller-b");
        }
    }
}
