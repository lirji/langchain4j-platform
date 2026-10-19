package com.lrj.platform.knowledge.ingest.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.lrj.platform.knowledge.ingest.job.PreparedIngestionFixtures.*;
import static org.assertj.core.api.Assertions.*;

/** 默认 H2+内存；显式测试环境参数改为随机 MySQL schema 与 S3 前缀，绝不接入业务库。 */
class PreparedIngestionPersistenceTest {
    @Test
    void recreatedStoresReuseFrozenResultAfterPartialSuccessAndFenceReferenceReplacement() throws Exception {
        String url = System.getenv("MP_INGEST_TEST_DB_URL");
        String endpoint = System.getenv("PREPARED_IT_S3_ENDPOINT");
        if (url != null && !url.contains("/lc4j_mp_it_")) throw new IllegalArgumentException("isolated schema required");
        var dataSource = new DriverManagerDataSource(url == null
                ? "jdbc:h2:mem:prepared-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1" : url,
                url == null ? "sa" : System.getenv("MP_TEST_DB_USER"),
                url == null ? "" : System.getenv("MP_TEST_DB_PASSWORD"));
        SchemaMigrationRunner.migrate(dataSource, SchemaName.KNOWLEDGE_INGESTION);
        if (url != null) {
            // 同一随机 schema 内其他 Mapper 用例也会留任务，清空测试夹具避免 reconciler 扫到前一用例。
            new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("DELETE FROM KNOWLEDGE_INGESTION_JOB");
        }
        var mapper = new ObjectMapper();
        var firstJobs = new JdbcIngestionJobStore(dataSource, mapper);
        var job = firstJobs.createOrGet(job("restore-" + UUID.randomUUID()));
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        String prefix = "documents/prepared-it/" + UUID.randomUUID();
        S3Client client = endpoint == null ? null : client(endpoint);
        DocumentSourceStore objects = client == null ? new InMemoryDocumentSourceStore()
                : new S3DocumentSourceStore(client, System.getenv("PREPARED_IT_S3_BUCKET"), prefix);
        IngestionPreparation bound = null;
        try {
            var generated = prepared(job);
            var firstResults = new ObjectStorageIngestionPreparedStore(objects, mapper, new IngestionPreparedProperties(), Map.of());
            try (var firstWorker = new IngestionJobWorker(firstJobs, j -> generated, (j, p, sink) -> {
                assertThat(j.preparation().bound()).isTrue();
                if (sink == IngestionSink.ELASTICSEARCH) throw new IllegalStateException("ES unavailable");
            }, new NoopIngestionTaskLifecycle(), clock, new IngestionJobProperties(), firstResults)) {
                firstWorker.process(job.tenantId(), job.jobId());
            }
            var partial = firstJobs.find(job.tenantId(), job.jobId()).orElseThrow();
            bound = partial.preparation();
            assertThat(partial.status()).isEqualTo(IngestionStatus.PARTIAL);
            assertThat(partial.sinks().get(IngestionSink.VECTOR)).isEqualTo(IngestionSinkState.SUCCEEDED);
            // 新 JDBC mapper、S3 adapter、版本和 worker；不依赖旧 worker 的对象或结果缓存。
            var restartedJobs = new JdbcIngestionJobStore(dataSource, new ObjectMapper());
            DocumentSourceStore restartedObjects = client == null ? objects
                    : new S3DocumentSourceStore(client, System.getenv("PREPARED_IT_S3_BUCKET"), prefix);
            var changed = new IngestionPreparedProperties();
            changed.setProcessingVersion("different-parser-and-model");
            var restartedResults = new ObjectStorageIngestionPreparedStore(restartedObjects, mapper, changed,
                    Map.of("strategy", "changed"));
            var recoveryClock = Clock.offset(clock, Duration.ofSeconds(6));
            assertThat(new IngestionReconciler(restartedJobs, recoveryClock, new IngestionJobProperties()).reconcile()).isEqualTo(1);
            var indexed = new AtomicReference<PreparedIngestionDocument>();
            try (var restartedWorker = new IngestionJobWorker(restartedJobs,
                    j -> { throw new AssertionError("restart must not parse or embed again"); }, (j, p, sink) -> {
                        assertThat(sink).isNotEqualTo(IngestionSink.VECTOR);
                        indexed.set(p);
                    }, new NoopIngestionTaskLifecycle(), recoveryClock, new IngestionJobProperties(), restartedResults)) {
                assertThat(restartedWorker.process(job.tenantId(), job.jobId())).isTrue();
                assertThat(restartedWorker.process(job.tenantId(), job.jobId())).isFalse();
            }
            assertThat(indexed.get()).isEqualTo(generated);
            var ready = restartedJobs.find(job.tenantId(), job.jobId()).orElseThrow();
            assertThat(ready.status()).isEqualTo(IngestionStatus.READY);
            assertThat(ready.preparation()).isEqualTo(bound);
            assertThatThrownBy(() -> ready.withPreparation(IngestionPreparation.PENDING))
                    .isInstanceOf(IngestionJobConflictException.class);
            // 绕过领域 helper 构造待写对象，数据库自身也必须拒绝清除引用。
            var forged = new IngestionJob(ready.jobId(), ready.idempotencyKey(), ready.tenantId(), ready.userId(),
                    ready.scopes(), ready.department(), ready.traceId(), ready.documentId(), ready.displayName(),
                    ready.category(), ready.documentVersion(), ready.newDocument(), ready.revision(), ready.source(),
                    ready.status(), ready.sinks(), ready.requiredSinks(), ready.error(), ready.createdAt(), ready.updatedAt(),
                    ready.execution(), IngestionPreparation.PENDING);
            assertThatThrownBy(() -> restartedJobs.save(forged, ready.revision()))
                    .isInstanceOf(IngestionJobConflictException.class);
            assertThat(restartedJobs.find("other-tenant", ready.jobId())).isEmpty();
            if (client != null) {
                var reference = bound.result();
                assertThatThrownBy(() -> restartedObjects.open("other-tenant", reference)).isInstanceOf(SecurityException.class);
            }
        } finally {
            // 仅删除本测试写入并已知的对象；不扫描或删除共享 bucket/prefix 的其他数据。
            if (bound != null) objects.delete(job.tenantId(), bound.result());
            if (client != null) client.close();
        }
    }

    private S3Client client(String endpoint) {
        return S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        System.getenv("PREPARED_IT_S3_ACCESS_KEY"), System.getenv("PREPARED_IT_S3_SECRET_KEY"))))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
    }
}
