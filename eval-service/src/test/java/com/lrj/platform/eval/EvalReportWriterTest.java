package com.lrj.platform.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.eval.EvalCaseResult;
import com.lrj.platform.protocol.eval.EvalRunReply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesReportWhenDirectoryIsConfigured() throws Exception {
        EvalProperties properties = new EvalProperties();
        properties.setReportDirectory(tempDir.toString());
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EvalReportWriter writer = new EvalReportWriter(objectMapper, properties);
        EvalRunReply report = new EvalRunReply(
                1,
                1,
                1.0,
                List.of(new EvalCaseResult("case-1", true, 200, null, "ok", 3)),
                "run-1",
                "suite",
                "http://edge",
                Instant.parse("2026-07-06T00:00:00Z"),
                4,
                null,
                Instant.parse("2026-07-06T00:00:01Z"));

        var path = writer.write(report);

        assertThat(path).isPresent();
        assertThat(Files.readString(Path.of(path.get()))).contains("\"runId\" : \"run-1\"");
    }

    @Test
    void skipsWriteWhenDirectoryIsBlank() {
        EvalReportWriter writer = new EvalReportWriter(new ObjectMapper().findAndRegisterModules(), new EvalProperties());

        var path = writer.write(new EvalRunReply(0, 0, 0.0, List.of(), Instant.now()));

        assertThat(path).isEmpty();
    }
}
