package com.lrj.platform.knowledge.ingest.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static com.lrj.platform.knowledge.ingest.sync.SyncData.*;
import static com.lrj.platform.protocol.knowledge.KnowledgeSourceFeed.*;

/** 以真实关系库检查控制CAS、检查点和运行记录的原子性；支持隔离MySQL执行。 */
class KnowledgeSyncOperationsTest {
    private final javax.sql.DataSource db = SyncTestSupport.database();
    private final SyncTestSupport.MutableClock clock = new SyncTestSupport.MutableClock();
    private final KnowledgeSyncStore store = new KnowledgeSyncStore(db, new ObjectMapper(), clock);
    private final KnowledgeSyncProperties.Source source = SyncTestSupport.source("ops-" + UUID.randomUUID());
    private final Planned item = new Planned(new Item("faq", 1, "title", "private content", false), "doc", 1, "hash", "CREATED");
    private final Pending page = new Pending(List.of(item), "private cursor", 0);

    @Test void pauseFencesOldCoordinatorAndResumePreservesRunAndPendingIntent() {
        var old = store.claim(source, "operator"); store.stage(old, page);
        String id = old.activeRunId;
        store.control(source, 0, true);
        assertThat(store.get(source).paused).isTrue();
        assertThat(store.run(source, id).status()).isEqualTo("PAUSED");
        assertThatThrownBy(() -> store.completed(old, item, page.advance())).hasMessage("LEASE_LOST");
        assertThat(store.document(old, "faq")).isNull();
        assertThat(store.get(source).cursorValue).isEmpty();
        clock.advance(); assertThat(store.claim(source)).isNull();
        assertThatThrownBy(() -> store.wake(source)).hasMessage("SOURCE_PAUSED");
        assertThatThrownBy(() -> store.control(source, 0, false)).hasMessage("CONTROL_CONFLICT");
        store.control(source, 1, false);
        var restarted = new KnowledgeSyncStore(db, new ObjectMapper(), clock);
        var resumed = restarted.claim(source);
        assertThat(resumed.activeRunId).isEqualTo(id);
        assertThat(restarted.pending(resumed)).isEqualTo(page);
        restarted.completed(resumed, item, page.advance()); restarted.commit(resumed, page.nextCursor());
        var run = restarted.run(source, id);
        assertThat(run.status()).isEqualTo("SUCCEEDED"); assertThat(run.triggeredBy()).isEqualTo("operator");
        assertThat(run.executionUserId()).isEqualTo("sync-user"); assertThat(run.getCreated()).isEqualTo(1);
        assertThat(run.remaining()).isZero(); assertThat(run.finishedAt()).isEqualTo(clock.millis());
        assertThat(restarted.get(source).lastSuccessAt).isEqualTo(clock.millis());
        assertThat(restarted.get(source).pendingJson).isNull();
    }
    @Test void retriesReplaceLatestRecordAndDoNotDoubleCountOrLeakContent() throws Exception {
        var row = store.claim(source); store.stage(row, page);
        String id = row.activeRunId;
        store.release(row, "INGESTION_REQUIRES_ATTENTION", "job");
        assertThat(store.run(source, id).getFailed()).isEqualTo(1);
        clock.advance(); row = store.claim(source); store.release(row, "INGESTION_REQUIRES_ATTENTION", "job");
        assertThat(store.run(source, id).getFailed()).isEqualTo(1);
        clock.advance(); row = store.claim(source); store.completed(row, item, page.advance()); store.commit(row, page.nextCursor());
        var run = store.run(source, id);
        assertThat(run.records()).hasSize(1); assertThat(run.getFailed()).isZero(); assertThat(run.getCreated()).isEqualTo(1);
        assertThat(run.errorCode()).isNull();
        assertThat(new ObjectMapper().writeValueAsString(run)).doesNotContain("private content", "private cursor", "secret", "https://");
    }
    @Test void paginationRemainsStableWhenNewRunArrivesAndIsolatesTenants() {
        for (int i = 0; i < 3; i++) { var row = store.claim(source); store.commit(row, "c" + i); clock.advance(); }
        var first = store.runs(source, null, 2); assertThat(first.items()).hasSize(2); assertThat(first.nextCursor()).isNotNull();
        var row = store.claim(source); store.commit(row, "new");
        var second = store.runs(source, first.nextCursor(), 2);
        assertThat(second.items()).hasSize(1); assertThat(second.nextCursor()).isNull();
        assertThat(first.items()).doesNotContainAnyElementsOf(second.items());
        var other = SyncTestSupport.source(source.tenantId().toUpperCase());
        assertThat(store.runs(other, null, 20).items()).isEmpty();
        assertThat(store.run(other, first.items().getFirst().id())).isNull();
        assertThatThrownBy(() -> store.runs(source, "bad", 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.runs(source, null, 51)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rollbackKeepsRunCountersAndCheckpointAligned() {
        var row = store.claim(source); store.stage(row, page); store.completed(row, item, page.advance());
        var before = store.run(source, row.activeRunId);
        assertThatThrownBy(() -> store.completed(row, item, new Pending(page.items(), "wrong", 2))).hasMessage("CHECKPOINT_CONFLICT");
        assertThat(store.run(source, row.activeRunId)).isEqualTo(before);
        assertThat(store.pending(store.get(source)).index()).isEqualTo(1);
    }
    @Test void concurrentControlsCannotBothWinAndFinishedRunRemainsImmutable() throws Exception {
        var row = store.claim(source); store.commit(row, "done");
        var before = store.runs(source, null, 20).items().getFirst();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> control = () -> { gate.await(); try { store.control(source, 0, true); return true; } catch (SyncFailure conflict) { return false; } };
            var first = pool.submit(control); var second = pool.submit(control); gate.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(store.run(source, before.id())).isEqualTo(before);
    }
    @Test void retryCannotClaimANewPageAfterOriginalJobCompleted() {
        var row = store.claim(source); store.stage(row, page); store.release(row, "INGESTION_REQUIRES_ATTENTION", "original-job");
        clock.advance(); row = store.claim(source); store.completed(row, item, page.advance()); store.commit(row, page.nextCursor());
        store.wake(source);
        assertThatThrownBy(() -> store.claim(source, "operator", "original-job")).hasMessage("NO_RETRYABLE_JOB");
        assertThat(store.get(source).activeRunId).isNull(); assertThat(store.runs(source, null, 20).items()).hasSize(1);
    }
    @Test void oldSnapshotResumesWithoutInventingHistoricalActionCounts() throws Exception {
        var row = store.claim(source);
        String oldJson = "{\"items\":[{\"item\":{\"id\":\"legacy\",\"revision\":1,\"title\":\"title\",\"content\":\"body\",\"deleted\":false},\"documentId\":\"doc\",\"documentVersion\":1,\"hash\":\"hash\"}],\"nextCursor\":\"next\",\"index\":1}";
        // 模拟V7升级时已有的页检查点；仅修改本测试随机租户，业务SQL仍只在Mapper中。
        new org.springframework.jdbc.core.JdbcTemplate(db).update("UPDATE KNOWLEDGE_HTTP_SYNC SET PENDING_JSON=?,ACTIVE_RUN_ID=NULL,LEASE_OWNER=NULL,LEASE_UNTIL=0 WHERE TENANT_KEY=? AND SOURCE_KEY=?", oldJson, row.tenantKey, row.sourceKey);
        row = store.claim(source); var run = store.run(source, row.activeRunId);
        assertThat(run.fetchedCount()).isEqualTo(1); assertThat(run.remaining()).isZero();
        assertThat(run.getCreated()).isZero(); assertThat(run.getLegacyConfirmed()).isEqualTo(1);
        store.commit(row, "next"); assertThat(store.get(source).cursorValue).isEqualTo("next");
    }
    @Test void sourceFailureHasUnknownCountInsteadOfPretendingToHaveProcessedZeroRows() {
        var row = store.claim(source); store.release(row, "SOURCE_HTTP_ERROR", null);
        var run = store.run(source, row.activeRunId);
        assertThat(run.fetchedCount()).isNull(); assertThat(run.errorCode()).isEqualTo("SOURCE_HTTP_ERROR");
        assertThat(run.getFailed()).isZero(); assertThat(run.records()).isEmpty();
    }
}
