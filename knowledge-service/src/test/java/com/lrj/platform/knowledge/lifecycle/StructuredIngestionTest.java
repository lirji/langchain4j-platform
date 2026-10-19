package com.lrj.platform.knowledge.lifecycle;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.knowledge.DocumentMirror;
import com.lrj.platform.knowledge.KnowledgeEmbeddingConfig;
import com.lrj.platform.knowledge.ingest.LlmContextualEnricher;
import com.lrj.platform.knowledge.ingest.NoopContextualEnricher;
import com.lrj.platform.knowledge.ingest.job.*;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 同步/异步实际编排都必须携带定位，不能只有孤立 parser 的测试通过。 */
class StructuredIngestionTest {
    private static final byte[] CSV="编号,名称,数量\n00123,部署手册,42\n".getBytes(StandardCharsets.UTF_8);
    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void asyncPreparationCarriesStructureIntoFinalVectors() throws Exception {
        var sources=new InMemoryDocumentSourceStore();
        var source=sources.put(new DocumentSourceStore.PutSource("acme","doc",1,"text/csv",CSV.length,"sha256:test",new ByteArrayInputStream(CSV)));
        var job=IngestionJob.received("job","key","acme","alice",Set.of("ingest"),"engineering","trace","doc","manual.csv",null,1,true,
                source,Set.of(IngestionSink.VECTOR),Instant.now());
        var preparer=new DefaultIngestionDocumentPreparer(sources,new DocumentTextExtractor(),StructuredDocumentParsingTest.factory("chars"),
                new NoopContextualEnricher(),new KnowledgeEmbeddingConfig.HashEmbeddingModel(),null,Clock.systemUTC());
        var prepared=preparer.prepare(job);
        assertThat(prepared.segments()).allSatisfy(chunk -> {
            assertThat(chunk.text()).contains("编号","00123");
            assertThat(chunk.metadata().getString("tenantId")).isEqualTo("acme");
            assertThat(chunk.metadata().getString("source_row_start")).isEqualTo("2");
        });
        assertThat(prepared.embeddings()).hasSameSizeAs(prepared.segments());
    }

    @Test
    void syncIngestionPreservesSourceAndSeparatesGeneratedPrefix() {
        TenantContext.set(new TenantContext.Tenant("acme","alice",Set.of("ingest")));
        var mirror=new DocumentMirror();
        var service=new DocumentService(new InMemoryEmbeddingStore<>(),new KnowledgeEmbeddingConfig.HashEmbeddingModel(),mirror,
                StructuredDocumentParsingTest.factory("chars"),new InMemoryDocumentRegistry(),mock(AuditLogger.class));
        service.setContextualEnricher(new LlmContextualEnricher((doc,chunk)->"Generated summary",1000));
        var parsed=new DocumentTextExtractor().parse(new ByteArrayInputStream(CSV),"manual.csv");
        service.uploadParsed("manual.csv","text/csv",parsed,null,CSV.length,false);
        assertThat(mirror.all()).allSatisfy(chunk -> {
            assertThat(chunk.text()).startsWith("Generated summary");
            assertThat(SourceProvenance.sourceText(chunk)).contains("00123").doesNotContain("Generated summary");
            assertThat(chunk.metadata().getString("source_row_start")).isEqualTo("2");
        });
    }
}
