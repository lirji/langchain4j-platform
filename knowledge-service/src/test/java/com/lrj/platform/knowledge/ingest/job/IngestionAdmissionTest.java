package com.lrj.platform.knowledge.ingest.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** 相同测试默认真实 H2 事务，显式配置时使用随机 MySQL schema 的多个独立 store。 */
class IngestionAdmissionTest {
    private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");

    @Test void concurrentReplicasCannotExceedGlobalOrTenantQuota() throws Exception {
        var stores = stores(); var first = stores.getFirst();
        for(int tenant=0;tenant<6;tenant++) for(int index=0;index<4;index++) first.createOrGet(job("t"+tenant,"j"+index));
        try(var pool=Executors.newFixedThreadPool(12)) {
            var start=new CountDownLatch(1); var futures=new ArrayList<java.util.concurrent.Future<java.util.Optional<IngestionJobStore.Claim>>>();
            for(int i=0;i<24;i++) { var store=stores.get(i%stores.size()); futures.add(pool.submit(()-> { start.await(); return store.claim(next(NOW,4,1)); })); }
            start.countDown(); var grants=new ArrayList<IngestionJobStore.Claim>();
            for(var future:futures) future.get(10,TimeUnit.SECONDS).ifPresent(grants::add);
            assertThat(grants).hasSize(4);
            assertThat(grants).extracting(c->c.leased().tenantId()).doesNotHaveDuplicates();
            assertThat(first.claim(next(NOW,4,1))).isEmpty();
            var released=grants.getFirst().leased();
            first.save(IngestionJobStateMachine.preparationFailed(released,"test",NOW).withExecution(IngestionExecution.EMPTY),released.revision());
            var next=stores.getLast().claim(next(NOW,4,1)).orElseThrow();
            assertThat(grants).noneMatch(c->c.leased().tenantId().equals(next.leased().tenantId()));
        }
    }

    @Test void coldTenantIsNotHiddenBehindHotBacklogAndTurnSurvivesNewStore() {
        var stores=stores(); var first=stores.getFirst();
        for(int i=0;i<100;i++) first.createOrGet(job("a-hot","j"+i));
        first.createOrGet(job("z-cold","one"));
        var hot=first.claim(next(NOW,4,1)).orElseThrow(); assertThat(hot.leased().tenantId()).isEqualTo("a-hot");
        var cold=stores.getLast().claim(next(NOW,4,1)).orElseThrow(); assertThat(cold.leased().tenantId()).isEqualTo("z-cold");
        assertThat(first.claim(next(NOW,4,1))).isEmpty();
        // 失败或崩溃后额度恢复依靠状态/租约，不能由新进程重置公平历史。
        first.save(IngestionJobStateMachine.preparationFailed(hot.leased(),"test",NOW).withExecution(IngestionExecution.EMPTY),hot.leased().revision());
        assertThat(stores.getLast().claim(next(NOW,4,1))).get().extracting(c->c.leased().tenantId()).isEqualTo("a-hot");
    }

    @Test void expiryAllowsOtherQueuedWorkAndFencesOldRevisionAfterRecovery() {
        var stores=stores(); var store=stores.getFirst();
        store.createOrGet(job("tenant","first")); store.createOrGet(job("tenant","second"));
        var original=store.claim(next(NOW,4,1)).orElseThrow().leased();
        assertThat(store.claim(next(NOW.plusSeconds(59),4,1))).isEmpty();
        var after=NOW.plusSeconds(61);
        var clock=java.time.Clock.fixed(after,java.time.ZoneOffset.UTC);
        assertThat(new IngestionReconciler(store,clock,new IngestionJobProperties()).reconcile()).isEqualTo(1);
        var next=stores.getLast().claim(next(after,4,1)).orElseThrow().leased();
        assertThat(next.execution().leaseOwner()).isNotEqualTo(original.execution().leaseOwner());
        assertThatThrownBy(()->store.save(original,original.revision())).isInstanceOf(IngestionJobConflictException.class);
    }

    @Test void inconsistentReplicaPolicyFailsClosed() {
        var stores=stores(); var first=stores.getFirst(); first.createOrGet(job("t","one"));
        first.claim(next(NOW,4,1));
        assertThatThrownBy(()->stores.getLast().claim(next(NOW,8,2)))
                .isInstanceOfSatisfying(IngestionResourceException.class,e->assertThat(e.error()).isEqualTo(IngestionResourceError.ADMISSION_CONFIG));
    }

    @Test void memoryAdapterUsesSameQuotaAndFairTurnRules() {
        var store=new InMemoryIngestionJobStore(); store.createOrGet(job("a","one"));store.createOrGet(job("a","two"));store.createOrGet(job("b","one"));
        assertThat(store.claim(next(NOW,2,1))).get().extracting(c->c.leased().tenantId()).isEqualTo("a");
        assertThat(store.claim(next(NOW,2,1))).get().extracting(c->c.leased().tenantId()).isEqualTo("b");
        assertThat(store.claim(next(NOW,2,1))).isEmpty();
    }

    private List<JdbcIngestionJobStore> stores() {
        String url=System.getenv("MP_INGEST_TEST_DB_URL");
        if(url!=null && !url.contains("/lc4j_mp_it_")) throw new IllegalArgumentException("isolated test schema required");
        var dataSource=new DriverManagerDataSource(url==null?"jdbc:h2:mem:admission-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1":url,
                url==null?"sa":System.getenv("MP_TEST_DB_USER"),url==null?"":System.getenv("MP_TEST_DB_PASSWORD"));
        SchemaMigrationRunner.migrate(dataSource,SchemaName.KNOWLEDGE_INGESTION);
        if(url!=null) {
            var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            jdbc.update("DELETE FROM KNOWLEDGE_INGESTION_JOB");jdbc.update("DELETE FROM KNOWLEDGE_INGESTION_TENANT_TURN");
            jdbc.update("UPDATE KNOWLEDGE_INGESTION_ADMISSION SET GRANT_SEQUENCE=0,GLOBAL_LIMIT=NULL,TENANT_LIMIT=NULL WHERE GUARD_ID=1");
        }
        return java.util.stream.IntStream.range(0,4).mapToObj(i->new JdbcIngestionJobStore(dataSource,new ObjectMapper())).toList();
    }
    static IngestionJobStore.ClaimRequest next(Instant now,int global,int tenant) {
        return new IngestionJobStore.ClaimRequest(null,null,now,Duration.ofSeconds(60),global,tenant);
    }
    static IngestionJob job(String tenant,String id) {
        return IngestionJob.received(id,"key-"+id,tenant,"user-"+tenant,Set.of("ingest"),"department", "trace-"+tenant,
                "doc-"+id,id+".txt",null,1,true,new DocumentSourceRef("sources",tenant+"/"+id,"sha256:source","text/plain",12),
                Set.of(IngestionSink.VECTOR,IngestionSink.REGISTRY),NOW);
    }
}
