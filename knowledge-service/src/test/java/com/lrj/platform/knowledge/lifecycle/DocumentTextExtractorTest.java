package com.lrj.platform.knowledge.lifecycle;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 离线确定性单测：Tika 对 text/plain 和 markdown 字节流的解析是稳定的，不连网不连模型。
 * 真实 PDF/Office 小型样本由 DocumentFormatExtractionTest 在内存中构造，避免漏测格式解析。
 */
class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void extractsPlainText() {
        String text = extractor.extract(bytes("knowledge base answer 42"), "note.txt");
        assertThat(text).contains("knowledge base answer 42");
    }

    @Test
    void extractsMarkdownBody() {
        String md = """
                # 部署指南
                先起 Milvus，再用 kb profile 启动。
                """;
        String text = extractor.extract(bytes(md), "guide.md");
        assertThat(text).contains("部署指南").contains("Milvus");
    }

    @Test
    void blankInputRejected() {
        assertThatThrownBy(() -> extractor.extract(bytes("   \n  "), "empty.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(DocumentParsingError.EMPTY.message());
    }

    @Test
    void outputLimitRejectsWholeDocumentRatherThanReturningPartialText() {
        var properties = new DocumentParsingProperties();
        properties.setMaxTextChars(40);
        var bounded = new DocumentTextExtractor(properties);
        assertThat(bounded.extract(bytes("small document"), "small.txt")).contains("small document");
        assertThatThrownBy(() -> bounded.extract(bytes("正文".repeat(100)), "secret-path.txt"))
                .isInstanceOfSatisfying(DocumentParsingException.class, ex -> {
                    assertThat(ex.error()).isEqualTo(DocumentParsingError.TEXT_LIMIT);
                    assertThat(ex.getMessage()).doesNotContain("secret-path");
                });
    }

    @Test
    void sourceIoFailureIsRetryableAndDoesNotExposeInternalDetails() {
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("s3://private/secret-document"); }
        };
        assertThatThrownBy(() -> extractor.extract(broken, "sensitive.txt"))
                .isInstanceOfSatisfying(DocumentParsingException.class, ex -> {
                    assertThat(ex.error()).isEqualTo(DocumentParsingError.SOURCE_READ);
                    assertThat(ex.error().retryable()).isTrue();
                    assertThat(ex.getMessage()).doesNotContain("private", "sensitive", "secret");
                    assertThat(ex.getCause()).isNotNull();
                });
    }

    @Test
    void invalidLimitsCannotDisableTheBudget() {
        var properties = new DocumentParsingProperties();
        assertThatThrownBy(() -> properties.setMaxTextChars(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxTextChars(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
