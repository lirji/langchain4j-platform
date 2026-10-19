package com.lrj.platform.knowledge.lifecycle;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.BlankDocumentException;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 把上传的二进制文档（PDF / Word / Excel / PPT / HTML / 纯文本…）抽成纯文本。
 *
 * <p>背后是 Apache Tika（{@link ApacheTikaDocumentParser}），一个 parser 吃几乎所有常见格式，
 * 自动按 magic bytes 嗅探类型，不依赖 filename 后缀。无状态线程安全，整个应用共用一个实例。
 *
 * <p>跟后续目录批量入库路径（文件夹批量入库走 {@code FileSystemDocumentLoader} +
 * easy-rag 的 Tika SPI 默认 parser）能力一致 —— 这里只是把同样的解析能力补给 per-tenant 的
 * {@code POST /rag/documents} 上传路径（之前它手动 UTF-8 解码、只收 {@code text/*}）。
 */
@Component
public class DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractor.class);

    private final ApacheTikaDocumentParser parser;

    /** 保留离线和既有测试调用方式，使用与 Spring 配置相同的缺省预算。 */
    public DocumentTextExtractor() {
        this(new DocumentParsingProperties());
    }

    /** 每次解析独享有界 handler，避免不同请求共享收集缓冲区或静默截断。 */
    @Autowired
    public DocumentTextExtractor(DocumentParsingProperties properties) {
        int limit = properties.getMaxTextChars();
        parser = new ApacheTikaDocumentParser(null, () -> new BodyContentHandler(limit), null, null, false);
    }

    /**
     * 解析输入流为纯文本。
     *
     * @param in       文档字节流（调用方负责关闭，如 {@code MultipartFile.getInputStream()}）
     * @param filename 原始文件名，仅用于清除控制字符并限长后的日志，不进入对外异常消息
     * @return 抽取出的正文，已 trim
     * @throws DocumentParsingException 解析失败或正文为空；输入问题返回 400，临时读取故障返回 503
     */
    public String extract(InputStream in, String filename) {
        try {
            Document doc = parser.parse(new SourceStream(in));
            String text = doc.text();
            if (text == null || text.isBlank()) {
                throw new DocumentParsingException(DocumentParsingError.EMPTY, null);
            }
            return text.trim();
        } catch (DocumentParsingException e) {
            throw e;
        } catch (Exception e) {
            DocumentParsingError error = classify(e);
            // 外部文件名可能含换行且长度不受信任；详细异常只留在服务端，正文从不写入日志。
            String safeName = filename == null ? "unknown" : filename.replaceAll("[\\r\\n\\t]", "_");
            if (safeName.length() > 256) safeName = safeName.substring(0, 256);
            log.warn("document parse failed file={} code={}", safeName, error.code(), e);
            throw new DocumentParsingException(error, e);
        }
    }

    private static DocumentParsingError classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof WriteLimitReachedException) return DocumentParsingError.TEXT_LIMIT;
            if (cause instanceof SourceReadException) return DocumentParsingError.SOURCE_READ;
            if (cause instanceof EncryptedDocumentException) return DocumentParsingError.ENCRYPTED;
            if (cause instanceof BlankDocumentException) return DocumentParsingError.EMPTY;
        }
        return DocumentParsingError.INVALID;
    }

    /** 只给原始流读取错误打标记，解析器对损坏格式抛出的 IOException 不应被无限重试。 */
    private static final class SourceStream extends FilterInputStream {
        SourceStream(InputStream input) { super(input); }

        @Override
        public int read() throws IOException {
            try { return in.read(); }
            catch (IOException ex) { throw new SourceReadException(ex); }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            try { return in.read(bytes, offset, length); }
            catch (IOException ex) { throw new SourceReadException(ex); }
        }

        @Override
        public long skip(long count) throws IOException {
            try { return in.skip(count); }
            catch (IOException ex) { throw new SourceReadException(ex); }
        }

        @Override
        public int available() throws IOException {
            try { return in.available(); }
            catch (IOException ex) { throw new SourceReadException(ex); }
        }
    }

    private static final class SourceReadException extends IOException {
        SourceReadException(IOException cause) { super(DocumentParsingError.SOURCE_READ.message(), cause); }
    }
}
