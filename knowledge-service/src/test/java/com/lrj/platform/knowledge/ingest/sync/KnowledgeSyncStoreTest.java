package com.lrj.platform.knowledge.ingest.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static com.lrj.platform.knowledge.ingest.sync.SyncData.*;
import static com.lrj.platform.protocol.knowledge.KnowledgeSourceFeed.*;

class KnowledgeSyncStoreTest {
    private final javax.sql.DataSource db = SyncTestSupport.database();
    private final SyncTestSupport.MutableClock clock = new SyncTestSupport.MutableClock();
    private final KnowledgeSyncStore store = new KnowledgeSyncStore(db, new ObjectMapper(), clock);
    private final KnowledgeSyncProperties.Source source = SyncTestSupport.source("tenant-" + UUID.randomUUID());

    @Test void leaseIsExclusiveAndOldExecutorCannotWriteAfterTakeover() {
        var old = store.claim(source);
        assertThat(store.claim(source)).isNull();
        clock.advance();
        var current = store.claim(source);
        assertThat(current.leaseOwner).isNotEqualTo(old.leaseOwner);
        assertThatThrownBy(() -> store.commit(old, "wrong")).isInstanceOf(SyncFailure.class);
        store.commit(current, "right");
        assertThat(store.get(source).cursorValue).isEqualTo("right");
        assertThat(store.claim(source)).isNull();
    }
    @Test void mappingAndPagePositionRollBackTogetherOnConflict() {
        var row = store.claim(source);
        var item = new Planned(new Item("faq1", 1, "title", "content", false), "doc", 1, "hash");
        var page = new Pending(List.of(item), "after", 0);
        store.stage(row, page);
        store.completed(row, item, page.advance());
        var persisted = store.get(source);
        long revision = persisted.revision;
        assertThatThrownBy(() -> store.completed(row, item, new Pending(List.of(item), "wrong", 2)))
                .isInstanceOf(SyncFailure.class);
        assertThat(store.get(source).revision).isEqualTo(revision);
        assertThat(store.pending(store.get(source)).index()).isEqualTo(1);
        assertThat(store.document(persisted, "faq1").sourceRevision).isEqualTo(1);
    }
    @Test void stateSurvivesRestartAndIsolatesCaseSensitiveTenants() {
        var row = store.claim(source);
        var page = new Pending(List.of(new Planned(new Item("faq1", 1, "title", "body", false), "doc", 1, "hash")), "next", 0);
        store.stage(row, page);
        store.release(row, "SOURCE_HTTP_ERROR", null);
        var restarted = new KnowledgeSyncStore(db, new ObjectMapper(), clock);
        assertThat(restarted.pending(restarted.get(source))).isEqualTo(page);
        assertThat(restarted.get(SyncTestSupport.source(source.tenantId().toUpperCase()))).isNull();
        assertThat(restarted.get(source).cursorValue).isEmpty();
    }
    @Test void credentialRotationKeepsCheckpointButEndpointChangeFails() {
        var row = store.claim(source); store.commit(row, "done"); clock.advance();
        var rotated = new KnowledgeSyncProperties.Source(source.id(), source.tenantId(), source.userId(), source.department(),
                source.endpoint(), "rotated-secret", false);
        var next = store.claim(rotated); assertThat(next.cursorValue).isEqualTo("done");
        store.commit(next, "done"); clock.advance();
        var changed = new KnowledgeSyncProperties.Source(source.id(), source.tenantId(), source.userId(), source.department(),
                java.net.URI.create("https://other.invalid/feed"), "rotated-secret", false);
        assertThatThrownBy(() -> store.claim(changed)).isInstanceOf(SyncFailure.class).hasMessage("SOURCE_CONFIG_CHANGED");
    }
    @Test void concurrentClaimsHaveOneWinner() throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var one = pool.submit(() -> { gate.await(); return store.claim(source); });
            var two = pool.submit(() -> { gate.await(); return store.claim(source); }); gate.countDown();
            assertThat(java.util.stream.Stream.of(one.get(), two.get()).filter(java.util.Objects::nonNull).count()).isEqualTo(1);
        }
    }
}
