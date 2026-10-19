package com.lrj.platform.knowledge.lifecycle;

import com.lrj.platform.knowledge.DocumentSplitterFactory;
import com.lrj.platform.knowledge.ingest.LlmContextualEnricher;
import com.lrj.platform.knowledge.ingest.job.IngestionModelWork;
import com.lrj.platform.knowledge.ingest.job.IngestionResourceProperties;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** 固定格式和确定性模型桩的成本/结构对照，不代表真实 embedding 的 Recall 或提供方金额。 */
class DocumentProcessingCostTest {
    private final EmbeddingModel model=segments->Response.from(segments.stream().map(s->Embedding.from(new float[]{1,0})).toList());

    @Test void writesReproducibleStructureModelAndPayloadComparison() throws Exception {
        var report=new LinkedHashMap<String,Object>();
        report.put("scope","synthetic structure and logical-cost comparison; deterministic stub; no provider billing or online recall");
        var rows=new ArrayList<Map<String,Object>>();
        var sample=DocumentParsingCorpus.samples().stream().filter(s->s.id().equals("csv-long-rows")).findFirst().orElseThrow();
        ParsedDocument parsed;
        try(var parser=new DocumentTextExtractor()) {parsed=parser.parse(new ByteArrayInputStream(sample.bytes()),sample.filename());}
        for(boolean structured:List.of(false,true)) {
            var work=work();var factory=factory("semantic");
            List<TextSegment> chunks=structured?factory.split(parsed,Metadata.from("tenantId","fixture"),work)
                    :factory.create(work).split(Document.from(parsed.text()));
            long paired=sample.rowMarkers().stream().filter(marker->chunks.stream().anyMatch(s->s.text().contains(marker)
                    && sample.headers().stream().allMatch(s.text()::contains))).count();
            if(structured) {assertThat(paired).isEqualTo(100);assertThat(work.snapshot().usage().getFirst().logicalCalls()).isZero();}
            else assertThat(work.snapshot().usage().getFirst().logicalCalls()).isPositive();
            work.validateMetadata(chunks);work.embed(model,chunks,IngestionModelWork.Stage.FINAL_EMBEDDING);
            rows.add(Map.of("case",structured?"csv-structure-first":"csv-flat-semantic","chunks",chunks.size(),"rowsWithHeader",paired,"modelWork",work.snapshot()));
        }
        report.put("table",rows);
        var payload=new ArrayList<Map<String,Object>>();
        String longText="Technical manual. Keep the invoice identifier and recover safely. ".repeat(120);
        for(String strategy:List.of("recursive","parent-child")) {
            var work=work();var chunks=factory(strategy).create(work).split(Document.from(longText));
            work.validateMetadata(chunks);
            payload.add(Map.of("strategy",strategy,"sourceBytes",longText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    "chunks",chunks.size(),"metadataBytes",work.snapshot().metadataBytes(),"repeatedParentBytes",work.snapshot().repeatedParentBytes()));
            if(strategy.equals("parent-child")) assertThat(work.snapshot().repeatedParentBytes()).isGreaterThan(longText.length());
        }
        report.put("parentPayload",payload);
        var enhancement=new ArrayList<Map<String,Object>>();
        for(boolean enabled:List.of(false,true)) {
            var work=work();List<TextSegment> chunks=List.of(TextSegment.from("INVOICE_APPROVAL"),TextSegment.from("PAYMENT_AFTER_APPROVAL"));
            if(enabled) chunks=new LlmContextualEnricher((window,chunk)->"Invoice workflow context",8000).enrich("",chunks,work);
            assertThat(chunks).allSatisfy(s->assertThat(SourceProvenance.sourceText(s)).isIn("INVOICE_APPROVAL","PAYMENT_AFTER_APPROVAL"));
            work.validateMetadata(chunks);work.embed(model,chunks,IngestionModelWork.Stage.FINAL_EMBEDDING);
            enhancement.add(Map.of("enabled",enabled,"modelWork",work.snapshot()));
        }
        report.put("contextual",enhancement);
        var path=Path.of("target/knowledge-parsing-hardening/kdp06d/cost-report.json");Files.createDirectories(path.getParent());
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),report);
    }
    private IngestionModelWork work() {return new IngestionModelWork(new IngestionResourceProperties(),()->{});}
    private DocumentSplitterFactory factory(String strategy) {
        return new DocumentSplitterFactory(strategy,"chars",300,0,0,"gpt-4o-mini","recursive",1200,0,0,95,300,0,model);
    }
}
