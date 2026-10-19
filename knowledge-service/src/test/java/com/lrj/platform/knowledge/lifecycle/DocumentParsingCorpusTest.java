package com.lrj.platform.knowledge.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.knowledge.ChunkIndexing;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/** 内容断言是门禁，结构/定位和资源是测量值；不把标记召回率称为检索质量。 */
class DocumentParsingCorpusTest {
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private static final Path OUTPUT = Path.of("target/knowledge-parsing-hardening/corpus");

    @TestFactory
    Stream<DynamicTest> documentCorpus() throws Exception {
        Files.createDirectories(OUTPUT.resolve("fixtures"));
        var extractor = new DocumentTextExtractor();
        return DocumentParsingCorpus.samples().stream().map(sample -> DynamicTest.dynamicTest(sample.id(), () -> {
            Files.write(OUTPUT.resolve("fixtures").resolve(sample.id() + "-" + sample.filename()), sample.bytes());
            var row = new LinkedHashMap<String, Object>();
            row.put("id", sample.id()); row.put("filename", sample.filename());
            row.put("inputBytes", sample.bytes().length);
            row.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sample.bytes())));
            row.put("expectedLocator", sample.locatorKind());
            row.put("actualSplitter", "recursive/chars/300/50");
            long before = System.nanoTime();
            long heapBefore = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            String text = null; String error = null;
            try (var input = new ByteArrayInputStream(sample.bytes())) { text = extractor.extract(input, sample.filename()); }
            catch (DocumentParsingException ex) { error = ex.error().code(); }
            row.put("parseNanos", System.nanoTime() - before);
            row.put("heapBeforeBytes", heapBefore);
            row.put("heapAfterBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            row.put("error", error);
            var segments = text == null ? List.<TextSegment>of()
                    : ChunkIndexing.sequential(DocumentSplitters.recursive(300, 50).split(Document.from(text)));
            String finalText = text == null ? "" : text;
            var missing = sample.expected().stream().filter(value -> !finalText.contains(value)).toList();
            row.put("textChars", finalText.length()); row.put("chunks", segments.size());
            row.put("missingFragments", missing);
            row.put("contentMarkerRecall", sample.expected().isEmpty() ? null
                    : (double) (sample.expected().size() - missing.size()) / sample.expected().size());
            long relatedRows = sample.rowMarkers().stream().filter(marker -> segments.stream().anyMatch(segment ->
                    segment.text().contains(marker) && sample.headers().stream().allMatch(segment.text()::contains))).count();
            row.put("rowsWithHeaderInSameChunk", relatedRows);
            row.put("expectedRows", sample.rowMarkers().size());
            row.put("chunksWithLocator", segments.stream().filter(segment -> segment.metadata().containsKey("source_location")).count());
            row.put("chunksWithHeading", segments.stream().filter(segment -> segment.metadata().containsKey("section")).count());
            RESULTS.add(row);
            assertThat(error).isEqualTo(sample.expectedError());
            assertThat(missing).as(sample.id() + " expected content").isEmpty();
            if (error == null) assertThat(segments).isNotEmpty();
        }));
    }

    @AfterAll
    static void writeReport() throws Exception {
        Files.createDirectories(OUTPUT);
        var report = new LinkedHashMap<String, Object>();
        report.put("corpusVersion", DocumentParsingCorpus.VERSION);
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("maxTextChars", DocumentParsingProperties.DEFAULT_MAX_TEXT_CHARS);
        report.put("measurementScope", "single JVM, includes first-use initialization; heap snapshots are not peak RSS; no model or retrieval call");
        report.put("results", RESULTS);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(OUTPUT.resolve("report.json").toFile(), report);
    }
}
