package com.lrj.platform.knowledge.ingest.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.knowledge.authz.NoopKnowledgeAuthz;
import com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry;
import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** H2 常规执行；同一测试通过现有隔离 schema 工具在 dev_infra MySQL 上验证真实锁和事务。 */
class JdbcIngestionContentDedupTest {
    private DataSource source;
    private JdbcIngestionJobStore firstStore, secondStore;
    private final String tenant = "dedup-" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        String url = System.getenv("MP_INGEST_TEST_DB_URL");
        if (url != null && !url.contains("/lc4j_mp_it_")) throw new IllegalArgumentException("isolated test schema required");
        source = new DriverManagerDataSource(url == null ? "jdbc:h2:mem:" + tenant + ";MODE=MySQL;DB_CLOSE_DELAY=-1" : url,
                url == null ? "sa" : System.getenv("MP_TEST_DB_USER"), url == null ? "" : System.getenv("MP_TEST_DB_PASSWORD"));
        SchemaMigrationRunner.migrate(source, SchemaName.KNOWLEDGE_INGESTION);
        firstStore = new JdbcIngestionJobStore(source, new ObjectMapper());
        secondStore = new JdbcIngestionJobStore(source, new ObjectMapper());
    }

    @Test
    void twoIndependentSubmittersCheckingEmptySnapshotCreateExactlyOneTask() throws Exception {
        var barrier = new CyclicBarrier(2);
        var files = new InMemoryDocumentSourceStore() {
            @Override public DocumentSourceRef putFile(SourceDescriptor descriptor, Path file) throws IOException {
                try { barrier.await(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new IOException(failure); }
                return super.putFile(descriptor, file);
            }
        };
        var first = service(firstStore, files);
        var second = service(secondStore, files);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> submit(first, "a", "alice"));
            var b = executor.submit(() -> submit(second, "b", "bob"));
            var results = java.util.List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(IngestionJob.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(IngestionDuplicateException.class::isInstance)).hasSize(1);
            var duplicate = results.stream().filter(IngestionDuplicateException.class::isInstance)
                    .map(IngestionDuplicateException.class::cast).findFirst().orElseThrow();
            assertThat(duplicate.duplicate().jobId()).isNull();
            assertThat(new JdbcTemplate(source).queryForObject(
                    "SELECT COUNT(*) FROM KNOWLEDGE_INGESTION_JOB WHERE TENANT_ID=?", Long.class, tenant)).isEqualTo(1L);
            var winner = results.stream().filter(IngestionJob.class::isInstance).map(IngestionJob.class::cast).findFirst().orElseThrow();
            var snapshot = secondStore.contentSnapshot(tenant, winner.source().contentHash(), 10);
            assertThat(snapshot.generation()).isEqualTo(1);
            assertThat(snapshot.matches()).hasSize(1);
            assertThat(snapshot.truncated()).isFalse();
        }
    }

    @Test
    void snapshotRaceRejectsStaleCheckAndIdempotentReplayDoesNotAdvanceGeneration() {
        var a = job("a", "key-a", "doc-a", "hash");
        var initial = firstStore.contentSnapshot(tenant, "hash", 10);
        var created = secondStore.createIfContentUnchanged(a, initial.generation());
        assertThat(created).contains(a);
        assertThat(firstStore.createIfContentUnchanged(job("b", "key-b", "doc-b", "hash"), initial.generation())).isEmpty();
        assertThat(secondStore.createIfContentUnchanged(job("replay", "key-a", "doc-a", "hash"), initial.generation())).contains(a);
        assertThat(firstStore.contentSnapshot(tenant, "hash", 10).generation()).isEqualTo(1);
        assertThat(firstStore.contentSnapshot("other-tenant", "hash", 10).matches()).isEmpty();
    }

    @Test
    void failedInsertRollsBackGuardAndCandidateReadIsBoundedAndIgnoresDeletedJobs() {
        firstStore.createOrGet(job("a", "key-a", "doc-a", "hash"));
        assertThatThrownBy(() -> secondStore.createIfContentUnchanged(job("b", "key-b", "doc-a", "hash"), 1))
                .isInstanceOf(IngestionJobConflictException.class);
        assertThat(firstStore.contentSnapshot(tenant, "hash", 1).generation()).isEqualTo(1);
        firstStore.createOrGet(job("legacy", "key-legacy", "doc-legacy", "hash"));
        var bounded = secondStore.contentSnapshot(tenant, "hash", 1);
        assertThat(bounded.matches()).hasSize(1);
        assertThat(bounded.truncated()).isTrue();
        new JdbcTemplate(source).update("UPDATE KNOWLEDGE_INGESTION_JOB SET DELETED=1 WHERE TENANT_ID=? AND JOB_ID=?", tenant, "legacy");
        assertThat(firstStore.contentSnapshot(tenant, "hash", 1).truncated()).isFalse();
    }

    @Test
    void legacyJobsWithoutGuardRowsAreIncludedAndMigrationIsRepeatable() {
        firstStore.createOrGet(job("old", "old-key", "old-doc", "hash"));
        new JdbcTemplate(source).update("DELETE FROM KNOWLEDGE_INGESTION_CONTENT_GUARD WHERE TENANT_ID=?", tenant);
        var snapshot = secondStore.contentSnapshot(tenant, "hash", 10);
        assertThat(snapshot.generation()).isZero();
        assertThat(snapshot.matches()).extracting(IngestionJob::jobId).containsExactly("old");
        assertThat(SchemaMigrationRunner.migrate(source, SchemaName.KNOWLEDGE_INGESTION).migrationsExecuted()).isZero();
    }

    private Object submit(IngestionSubmissionService service, String key, String user) throws Exception {
        try { return IngestionContentDedupTest.upload(service, IngestionContentDedupTest.command(key, tenant, user, "doc-" + key, 1, "same bytes")); }
        catch (IngestionDuplicateException expected) { return expected; }
    }

    private IngestionSubmissionService service(IngestionJobStore store, DocumentSourceStore files) {
        return new IngestionSubmissionService(files, store, new InMemoryDocumentRegistry(), new NoopKnowledgeAuthz(),
                Clock.systemUTC(), Set.of(IngestionSink.VECTOR, IngestionSink.REGISTRY));
    }

    private IngestionJob job(String id, String key, String doc, String hash) {
        return IngestionJob.received(id, key, tenant, "alice", Set.of("ingest"), "dept", "trace", doc, doc, null, 1,
                true, new DocumentSourceRef("test", id, hash, "text/plain", 10), Set.of(IngestionSink.VECTOR), Instant.parse("2026-10-19T00:00:00Z"));
    }
}
