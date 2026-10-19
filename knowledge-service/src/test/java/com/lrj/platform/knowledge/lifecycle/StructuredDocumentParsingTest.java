package com.lrj.platform.knowledge.lifecycle;

import com.lrj.platform.knowledge.DocumentSplitterFactory;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** 使用独立预期语料验证内容/关系/定位，报告不把结构指标叫作在线检索召回率。 */
class StructuredDocumentParsingTest {
    @TempDir Path root;
    private static final Metadata IDENTITY = Metadata.from(Map.of("tenantId","acme","docId","manual","version","2"));

    @TestFactory
    List<DynamicTest> preservesCorpusMarkersAndRecordsStructure() throws Exception {
        var report = new ArrayList<Map<String,Object>>();
        var samples = DocumentParsingCorpus.samples();
        var tests = new ArrayList<DynamicTest>();
        for (var sample : samples) tests.add(DynamicTest.dynamicTest(sample.id(), () -> {
            if (sample.expectedError() != null) {
                assertThatThrownBy(() -> parse(sample.bytes(),sample.filename())).isInstanceOfSatisfying(DocumentParsingException.class,
                        ex -> assertThat(ex.error().code()).isEqualTo(sample.expectedError()));
                return;
            }
            var parsed = parse(sample.bytes(),sample.filename());
            assertThat(sample.expected()).allSatisfy(marker -> assertThat(parsed.text()).contains(marker));
            var chunks = factory("chars").split(parsed,IDENTITY);
            assertThat(chunks).allSatisfy(chunk -> {
                assertThat(chunk.text().length()).isLessThanOrEqualTo(300);
                assertThat(chunk.metadata().getString("tenantId")).isEqualTo("acme");
                assertThat(chunk.metadata().getString(ParsedDocument.SOURCE_LOCATION)).isNotBlank();
            });
            for (int index=0;index<chunks.size();index++) assertThat(chunks.get(index).metadata().getString("index")).isEqualTo(Integer.toString(index));
            long linked = sample.rowMarkers().stream().filter(marker -> chunks.stream().anyMatch(chunk -> chunk.text().contains(marker)
                    && sample.headers().stream().allMatch(chunk.text()::contains))).count();
            if (!sample.rowMarkers().isEmpty()) assertThat(linked).isEqualTo(sample.rowMarkers().size());
            report.add(Map.of("id",sample.id(),"format",parsed.format(),"chunks",chunks.size(),"rows",sample.rowMarkers().size(),
                    "rowsWithHeaderSameChunk",linked,"locatedChunks",chunks.stream().filter(c -> c.metadata().getString(ParsedDocument.SOURCE_LOCATION)!=null).count(),
                    "warnings",parsed.warnings().stream().map(ParsedDocument.Warning::code).toList()));
            Path output=Path.of("target/knowledge-parsing-hardening/kdp06b/structure-report.json");Files.createDirectories(output.getParent());
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        }));
        return tests;
    }

    @Test
    void csvKeepsEmptyColumnsQuotedNewlinesAndLogicalRowNumbers() {
        var parsed=parse("\uFEFFid,,description\r\n00123,,\"line1\nline2,\"\"quoted\"\"\"\r\n".getBytes(StandardCharsets.UTF_8),"rows.csv");
        assertThat(parsed.format()).isEqualTo("csv");
        var rows=parsed.blocks().getFirst().rows();
        assertThat(rows.get(1).number()).isEqualTo(2);
        assertThat(rows.get(1).cells()).containsExactly("00123","","line1\nline2,\"quoted\"");
    }

    @Test
    void mixedPdfHasExactPageAndVisibleMissingTextWarning() throws Exception {
        var sample=DocumentParsingCorpus.samples().stream().filter(s -> s.id().equals("pdf-mixed-image")).findFirst().orElseThrow();
        var parsed=parse(sample.bytes(),sample.filename());
        assertThat(parsed.blocks()).extracting(block -> block.location().page()).containsExactly(1,2);
        assertThat(parsed.warnings()).anySatisfy(w -> {
            assertThat(w.code()).isEqualTo(ParsingWarnings.PDF_NO_TEXT); assertThat(w.location().page()).isEqualTo(2);
        });
        assertThat(factory("chars").split(parsed,IDENTITY)).allSatisfy(chunk ->
                assertThat(chunk.metadata().getString("source_pages_without_text")).isEqualTo("2"));
    }

    @Test
    void markdownCodeIsIntactAndDoesNotCreateFakeSection() {
        String text="# Guide\r\n## Install\r\n```sh\r\n# not heading\r\necho ok\r\n```\r\n## Recover\r\nrollback\r\n";
        var parsed=parse(text.getBytes(StandardCharsets.UTF_8),"guide.md");
        var code=parsed.blocks().stream().filter(b -> b.kind().equals(ParsedDocument.CODE)).findFirst().orElseThrow();
        assertThat(code.text()).isEqualTo("```sh\r\n# not heading\r\necho ok\r\n```\r\n");
        assertThat(code.headings()).containsExactly("Guide","Install");
        assertThat(code.location().lineStart()).isEqualTo(3);assertThat(code.location().lineEnd()).isEqualTo(6);
        assertThat(factory("chars").split(parsed,IDENTITY)).anySatisfy(c -> assertThat(c.text()).isEqualTo(code.text()));
    }

    @Test
    void spreadsheetReportsFormulasMergedAndHiddenDataWithoutEvaluatingExternalLinks() throws Exception {
        try(var book=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("data");var header=sheet.createRow(0);header.createCell(0).setCellValue("id");header.createCell(1).setCellValue("amount");
            var row=sheet.createRow(1);row.createCell(0).setCellValue("00001");var formula=row.createCell(1);formula.setCellFormula("1+2");
            book.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
            row.setZeroHeight(true);sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(3,3,0,1));
            book.write(bytes);var parsed=parse(bytes.toByteArray(),"data.xlsx");
            assertThat(parsed.text()).contains("00001,3");
            assertThat(parsed.warnings()).extracting(ParsedDocument.Warning::code)
                    .contains(ParsingWarnings.FORMULA_CACHE,ParsingWarnings.HIDDEN_CONTENT,ParsingWarnings.MERGED_CELLS);
        }
    }

    @Test
    void oversizedCodePreservesEveryIndentAndNewlineAcrossFragments() {
        String code = "```python\r\n" + "    print('中😀')  \r\n".repeat(60) + "```\r\n";
        var parsed = parse(code.getBytes(StandardCharsets.UTF_8),"code.md");
        for (String unit : List.of("chars","tokens")) {
            var chunks = factory(unit).split(parsed,IDENTITY);
            assertThat(chunks.stream().map(dev.langchain4j.data.segment.TextSegment::text)
                    .collect(java.util.stream.Collectors.joining())).isEqualTo(code);
            assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.metadata().getString("source_code_fragment")).isNotBlank());
        }
    }

    @Test
    void wideAndLongRowsKeepAllCellContentWithinCharactersAndTokens() {
        String header="id,"+java.util.stream.IntStream.range(1,20).mapToObj(i -> "column_"+i).collect(java.util.stream.Collectors.joining(","));
        String row="00001,"+java.util.stream.IntStream.range(1,20).mapToObj(i -> "CELL_"+i+"_"+"中😀".repeat(20)).collect(java.util.stream.Collectors.joining(","));
        var parsed=parse((header+"\n"+row+"\n").getBytes(StandardCharsets.UTF_8),"wide.csv");
        for(String unit:List.of("chars","tokens")) {
            var chunks=factory(unit).split(parsed,IDENTITY);
            var estimator=new OpenAiTokenCountEstimator("gpt-4o-mini");
            String joined=chunks.stream().map(dev.langchain4j.data.segment.TextSegment::text).collect(java.util.stream.Collectors.joining());
            for(int index=1;index<20;index++) assertThat(joined).contains("CELL_"+index+"_");
            assertThat(chunks).allSatisfy(c -> {
                assertThat("tokens".equals(unit)?estimator.estimateTokenCountInText(c.text()):c.text().length()).isLessThanOrEqualTo(300);
                assertThat(c.metadata().getString("source_row_start")).isEqualTo("2");
                assertThat(c.metadata().getString("source_columns")).isNotBlank();
                assertThat(c.text()).doesNotContain("�");
            });
        }
    }

    @Test
    void structureBudgetAndUnknownSchemaFailClosed() {
        var properties=new DocumentParsingProperties();properties.setExecutionMode(DocumentParsingProperties.IN_PROCESS);properties.setMaxStructureUnits(5);
        assertThatThrownBy(() -> new DocumentTextExtractor(properties).parse(new ByteArrayInputStream("a,b\n1,2\n".getBytes()),"x.csv"))
                .isInstanceOfSatisfying(DocumentParsingException.class,ex -> assertThat(ex.error()).isEqualTo(DocumentParsingError.STRUCTURE_LIMIT));
        assertThatThrownBy(() -> ParsedDocumentCodec.read("{\"schemaVersion\":99,\"format\":\"csv\",\"blocks\":[],\"warnings\":[]}".getBytes(),properties))
                .isInstanceOf(DocumentParsingException.class);
    }

    @Test
    void wordCustomOutlineStylePreservesChapterAndParagraphOrder() throws Exception {
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            var heading = document.createParagraph();
            heading.getCTP().addNewPPr().addNewOutlineLvl().setVal(java.math.BigInteger.ZERO);
            heading.createRun().setText("自定义标题");
            document.createParagraph().createRun().setText("本章正文");
            document.write(bytes);
            var parsed = parse(bytes.toByteArray(), "guide.docx");
            assertThat(parsed.blocks().get(1).headings()).containsExactly("自定义标题");
            assertThat(parsed.blocks().get(1).location().paragraph()).isEqualTo(2);
        }
    }

    @Test
    void parentIdentifiersDoNotCollideAcrossStructuredPages() throws Exception {
        var sample = DocumentParsingCorpus.samples().stream().filter(s -> s.id().equals("pdf-two-pages")).findFirst().orElseThrow();
        var factory = new DocumentSplitterFactory("parent-child","chars",300,50,0,"gpt-4o-mini","recursive",1200,0,1,95,1000,0,null);
        var chunks = factory.split(parse(sample.bytes(),sample.filename()),IDENTITY);
        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(c -> c.metadata().getString(com.lrj.platform.knowledge.ParentChildSplitter.PARENT_ID)).doesNotHaveDuplicates();
        assertThat(chunks.get(1).metadata().getString("source_page")).isEqualTo("2");
    }

    @Test
    void realForkTransfersStructuredRowsAndWarnings() {
        var properties=new DocumentParsingProperties();properties.setTempRoot(root);
        try(var extractor=new DocumentTextExtractor(properties)) {
            var parsed=extractor.parse(new ByteArrayInputStream("id,value\n001,hello\n".getBytes()),"sample.csv");
            assertThat(parsed.blocks().getFirst().rows().get(1).cells()).containsExactly("001","hello");
            assertThat(parsed.warnings()).extracting(ParsedDocument.Warning::code).contains(ParsingWarnings.HEADER_ASSUMED);
        }
    }
    private ParsedDocument parse(byte[] bytes,String filename) { return new DocumentTextExtractor().parse(new ByteArrayInputStream(bytes),filename); }
    static DocumentSplitterFactory factory(String unit) {
        return new DocumentSplitterFactory("recursive",unit,300,50,0,"gpt-4o-mini","recursive",1200,0,1,95,1000,0,null);
    }
}
