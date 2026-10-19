package com.lrj.platform.knowledge.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 使用实际打包 artifact，验证 Spring Boot 嵌套依赖的子进程入口，不把 Maven classpath 成功等同于镜像可用。 */
class ParsingArtifactTest {
    @TempDir Path directory;

    @Test
    void forkedParserWorksFromRepackagedBootArtifact() throws Exception {
        String artifact = System.getProperty("parsing.test.artifact");
        assumeTrue(artifact != null, "requires explicit freshly packaged artifact");
        assertThat(Path.of(artifact)).isRegularFile();
        String previous = System.getProperty("java.class.path");
        var properties = new DocumentParsingProperties(); properties.setTempRoot(directory);
        var samples = DocumentParsingCorpus.samples();
        try (var extractor = new DocumentTextExtractor(properties)) {
            System.setProperty("java.class.path", Path.of(artifact).toAbsolutePath().toString());
            for (String id : new String[]{"pdf-two-pages", "docx-headings-table", "xlsx-long-rows", "csv-long-rows"}) {
                var sample = samples.stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
                String text = extractor.extract(new ByteArrayInputStream(sample.bytes()), sample.filename());
                assertThat(sample.expected()).allMatch(text::contains);
            }
        } finally { System.setProperty("java.class.path", previous); }
        try (var files = Files.list(directory)) { assertThat(files).isEmpty(); }
    }
}
