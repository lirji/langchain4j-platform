package com.lrj.platform.knowledge.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/** 启动真实 Java 子进程验证终止/内存/并发边界，不用 mock Future 的取消代表停止解析。 */
class ForkedDocumentParserTest {
    @TempDir Path root;

    @Test
    void realForkPreservesAllMainFormatCorpusAndErrorCodes() throws Exception {
        var properties = properties();
        try (var extractor = new DocumentTextExtractor(properties)) {
            for (var sample : DocumentParsingCorpus.samples()) {
                if (sample.expectedError() == null) {
                    String text = extractor.extract(new ByteArrayInputStream(sample.bytes()), sample.filename());
                    for (String expected : sample.expected()) assertThat(text).as(sample.id()).contains(expected);
                } else {
                    assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(sample.bytes()), sample.filename()))
                            .isInstanceOfSatisfying(DocumentParsingException.class,
                                    error -> assertThat(error.error().code()).isEqualTo(sample.expectedError()));
                }
            }
        }
        assertEmptyRoot();
    }

    @Test
    void inputAndPageBudgetsAreExplicitAndCleanupStillRuns() throws Exception {
        var properties = properties(); properties.setMaxInputBytes(2);
        try (var parser = new ForkedDocumentParser(properties)) {
            assertError(() -> parser.extract(input()), DocumentParsingError.INPUT_LIMIT);
        }
        properties.setMaxInputBytes(1024 * 1024); properties.setMaxPages(1);
        var pdf = DocumentParsingCorpus.samples().stream().filter(sample -> sample.id().equals("pdf-two-pages")).findFirst().orElseThrow();
        try (var parser = new ForkedDocumentParser(properties)) {
            assertError(() -> parser.extract(new ByteArrayInputStream(pdf.bytes())), DocumentParsingError.PAGE_LIMIT);
        }
        assertEmptyRoot();
    }

    @Test
    void hardDeadlineKillsAnUnresponsiveProcessThenNormalWorkStillSucceeds() throws Exception {
        var properties = properties(); properties.setTimeout(Duration.ofMillis(500));
        var launched = new AtomicReference<Process>();
        try (var parser = probe(properties, "stall", launched, new CountDownLatch(0))) {
            assertError(() -> parser.extract(input()), DocumentParsingError.TIMEOUT);
            assertThat(launched.get().isAlive()).isFalse();
        }
        properties.setTimeout(Duration.ofSeconds(20));
        try (var parser = new ForkedDocumentParser(properties)) { assertThat(parser.extract(input())).isEqualTo("hello"); }
        assertEmptyRoot();
    }

    @Test
    void crashHeapExhaustionAndOversizedOutputAreContained() throws Exception {
        for (String mode : new String[]{"crash", "heap", "output", "disk", "environment"}) {
            var properties = properties();
            if (mode.equals("output")) properties.setMaxResultBytes(32);
            if (mode.equals("disk")) properties.setMaxTemporaryBytes(1024);
            var process = new AtomicReference<Process>();
            try (var parser = probe(properties, mode, process, new CountDownLatch(0))) {
                if (mode.equals("environment")) {
                    // macOS 的 JVM 自动加入该编码键；它不是从服务环境透传的业务凭据。
                    assertThat(parser.extract(input()).split(",")).contains("LANG", "TMPDIR")
                            .allMatch(key -> java.util.Set.of("LANG", "TMPDIR", "__CF_USER_TEXT_ENCODING").contains(key));
                }
                else assertError(() -> parser.extract(input()), mode.equals("output") || mode.equals("disk")
                        ? DocumentParsingError.RESOURCE_LIMIT : DocumentParsingError.PROCESS_FAILED);
            }
            assertThat(process.get().isAlive()).isFalse();
            assertEmptyRoot();
        }
    }

    @Test
    void saturationRejectsAndShutdownActuallyStopsCurrentProcess() throws Exception {
        var properties = properties(); properties.setMaxProcesses(1);
        var entered = new CountDownLatch(1); var process = new AtomicReference<Process>();
        try (var parser = probe(properties, "stall", process, entered);
             var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = callers.submit(() -> assertError(() -> parser.extract(input()), DocumentParsingError.PROCESS_FAILED));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertError(() -> parser.extract(input()), DocumentParsingError.BUSY);
            parser.close();
            first.get(5, TimeUnit.SECONDS);
            assertThat(process.get().isAlive()).isFalse();
            assertError(() -> parser.extract(input()), DocumentParsingError.PROCESS_UNAVAILABLE);
        }
        assertEmptyRoot();
    }

    @Test
    void sourceReadFailureDoesNotLaunchAProcess() throws Exception {
        try (var parser = new ForkedDocumentParser(properties(), builder -> { throw new AssertionError("must not start"); })) {
            assertError(() -> parser.extract(new java.io.InputStream() {
                @Override public int read() throws IOException { throw new IOException("private storage address"); }
            }), DocumentParsingError.SOURCE_READ);
        }
        assertEmptyRoot();
    }

    @Test
    void slowSourceIsRejectedAtTheNextReadBoundary() throws Exception {
        var properties = properties(); properties.setInputTimeout(Duration.ofMillis(20));
        try (var parser = new ForkedDocumentParser(properties, builder -> { throw new AssertionError("must not start"); })) {
            assertError(() -> parser.extract(new java.io.InputStream() {
                @Override public int read(byte[] buffer, int offset, int length) {
                    try { Thread.sleep(50); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    buffer[offset] = 1; return 1;
                }
                @Override public int read() { return -1; }
            }), DocumentParsingError.TIMEOUT);
        }
        assertEmptyRoot();
    }

    private DocumentParsingProperties properties() {
        var value = new DocumentParsingProperties(); value.setTempRoot(root); value.setTimeout(Duration.ofSeconds(20)); return value;
    }
    private ForkedDocumentParser probe(DocumentParsingProperties properties, String mode,
                                       AtomicReference<Process> process, CountDownLatch entered) {
        return new ForkedDocumentParser(properties, builder -> {
            var command = new ArrayList<>(builder.command());
            command.set(command.indexOf(ParsingProcessMain.class.getName()), ProbeProcess.class.getName());
            command.add(mode); builder.command(command);
            Process result = builder.start(); process.set(result); entered.countDown(); return result;
        });
    }
    private void assertEmptyRoot() throws IOException { try (var files = Files.list(root)) { assertThat(files).isEmpty(); } }
    private static ByteArrayInputStream input() { return new ByteArrayInputStream("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, DocumentParsingError expected) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DocumentParsingException.class,
                error -> assertThat(error.error()).isEqualTo(expected));
    }

    /** 固定测试入口，生产从未使用本类或可配置任意 main。 */
    public static class ProbeProcess {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            switch (args[4]) {
                case "stall" -> { while (true) { try { Thread.sleep(1000); } catch (InterruptedException ignored) { } } }
                case "crash" -> System.exit(17);
                case "heap" -> Files.write(directory.resolve("oversized"), new byte[1_000_000_000]);
                case "output" -> Files.writeString(directory.resolve(ParsingProcessMain.OUTPUT), "x".repeat(1000));
                case "disk" -> Files.write(directory.resolve("extra"), new byte[8192]);
                case "environment" -> {
                    Files.writeString(directory.resolve(ParsingProcessMain.OUTPUT), String.join(",", System.getenv().keySet()));
                }
                default -> throw new IllegalArgumentException();
            }
        }
    }
}
