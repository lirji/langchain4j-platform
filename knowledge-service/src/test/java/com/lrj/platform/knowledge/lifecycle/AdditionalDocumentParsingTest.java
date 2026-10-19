package com.lrj.platform.knowledge.lifecycle;

import com.lrj.platform.knowledge.DocumentSplitterFactory;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 真实容器、正文、切分覆盖、损坏/预算与 fork 的格式专项，不以 MIME 注册代替验证。 */
class AdditionalDocumentParsingTest {
    @TempDir Path root;
    static List<AdditionalDocumentCorpus.Sample> samples() throws Exception {return AdditionalDocumentCorpus.samples();}

    @ParameterizedTest(name="{index}: {0}") @MethodSource("samples")
    void readsContentAndRetainsItThroughSplitting(AdditionalDocumentCorpus.Sample sample) throws Exception {
        try(var extractor=new DocumentTextExtractor()) {
            var parsed=extractor.parse(new ByteArrayInputStream(sample.bytes()),sample.filename());
            assertContent(sample,parsed);
            var chunks=factory().split(parsed,Metadata.from("tenantId","fixture"));
            assertThat(chunks).allSatisfy(s->assertThat(s.metadata().getString("tenantId")).isEqualTo("fixture"));
            String joined=String.join("\n",chunks.stream().map(s->s.text()).toList());
            for(String marker:sample.expected()) assertThat(joined).contains(marker);
        }
    }
    @Test void oldExcelRowsRetainHeadersAndSheetLocation() throws Exception {
        var sample=samples().stream().filter(s->s.id().equals("xls-table")).findFirst().orElseThrow();
        try(var extractor=new DocumentTextExtractor()) {
            var parsed=extractor.parse(new ByteArrayInputStream(sample.bytes()),sample.filename());
            assertThat(parsed.blocks()).allSatisfy(b->assertThat(b.kind()).isEqualTo(ParsedDocument.TABLE));
            var chunks=factory().split(parsed,Metadata.from("tenantId","fixture"));
            for(int row=1;row<=100;row++) {
                String marker=String.format(java.util.Locale.ROOT,"%05d",row);
                assertThat(chunks).anySatisfy(s->{assertThat(s.text()).contains(marker,"编号","名称","数量");
                    assertThat(s.metadata().getString("source_sheet")).isEqualTo("旧版表");});
            }
        }
    }
    @Test void forkedFormatsPreserveContentAndCleanup() throws Exception {
        var properties=new DocumentParsingProperties();properties.setTempRoot(root.resolve("parser"));
        var report=new java.util.ArrayList<java.util.Map<String,Object>>();
        try(var extractor=new DocumentTextExtractor(properties)) {
            for(var sample:samples()) {
                long started=System.nanoTime();var parsed=extractor.parse(new ByteArrayInputStream(sample.bytes()),sample.filename());
                assertContent(sample,parsed);
                report.add(java.util.Map.of("case",sample.id(),"format",parsed.format(),"bytes",sample.bytes().length,
                        "chars",parsed.text().length(),"markers",sample.expected().size(),"elapsedMs",(System.nanoTime()-started)/1_000_000,
                        "warnings",parsed.warnings().stream().map(ParsedDocument.Warning::code).toList()));
            }
        }
        try(var files=Files.list(properties.getTempRoot())) {assertThat(files).isEmpty();}
        var output=Path.of("target/knowledge-parsing-hardening/kdp06e/formats-report.json");Files.createDirectories(output.getParent());
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
    }
    @Test void malformedOfficeContainersFailWithoutPartialSuccess() throws Exception {
        try(var extractor=new DocumentTextExtractor()) {
            for(var sample:samples().stream().filter(s->List.of("doc-bilingual","xls-table","ppt-slides","pptx-slides","odt-bilingual").contains(s.id())).toList()) {
                var truncated=java.util.Arrays.copyOf(sample.bytes(),64);
                assertThatThrownBy(()->extractor.parse(new ByteArrayInputStream(truncated),sample.filename()))
                        .as(sample.id()).isInstanceOf(DocumentParsingException.class);
            }
        }
    }
    @ParameterizedTest(name="budget: {0}") @MethodSource("samples")
    void textBudgetNeverBecomesSilentTruncation(AdditionalDocumentCorpus.Sample sample) {
        var properties=new DocumentParsingProperties();properties.setExecutionMode(DocumentParsingProperties.IN_PROCESS);properties.setMaxTextChars(8);
        try(var extractor=new DocumentTextExtractor(properties)) {
            assertThatThrownBy(()->extractor.parse(new ByteArrayInputStream(sample.bytes()),sample.filename()))
                    .isInstanceOfSatisfying(DocumentParsingException.class,e->assertThat(e.error()).isEqualTo(DocumentParsingError.TEXT_LIMIT));
        }
    }
    private static void assertContent(AdditionalDocumentCorpus.Sample sample,ParsedDocument parsed) {
        assertThat(parsed.format()).as(sample.id()).isEqualTo(sample.format());
        for(String marker:sample.expected()) assertThat(parsed.text()).as(sample.id()).contains(marker);
        if(sample.id().startsWith("ppt")) assertThat(parsed.text().indexOf("SLIDE_ONE")).isLessThan(parsed.text().indexOf("SLIDE_TWO"));
        if(sample.id().startsWith("html")) assertThat(parsed.text()).doesNotContain("UNEXECUTED_SCRIPT_MARKER");
        if(!sample.id().startsWith("xls") && !sample.id().startsWith("txt"))
            assertThat(parsed.warnings()).extracting(ParsedDocument.Warning::code).contains(ParsingWarnings.STRUCTURE_UNAVAILABLE);
    }
    private static DocumentSplitterFactory factory() {
        return new DocumentSplitterFactory("recursive","chars",300,0,0,"gpt-4o-mini","recursive",1200,0,1,95,1000,0,
                values->Response.from(values.stream().map(s->Embedding.from(new float[]{1,0})).toList()));
    }
}
