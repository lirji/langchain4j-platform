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
 * <p>应用缺省在独立 JVM 中运行 Apache Tika，调用方仍负责关闭输入流。正文、页数及进程预算
 * 由类型化配置约束；离线无参构造保留进程内路径用于质量基线。目录批量加载的 SPI 路径不自动
 * 获得这里的隔离保证，接入时必须显式经过此入口。
 */
@Component
public class DocumentTextExtractor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractor.class);

    private final ApacheTikaDocumentParser parser;
    private final ForkedDocumentParser isolated;

    /** 离线基线使用进程内解析及同一正文预算；生产配置构造默认 forked。 */
    public DocumentTextExtractor() {
        this(offlineProperties());
    }

    private static DocumentParsingProperties offlineProperties() {
        var properties = new DocumentParsingProperties();
        properties.setExecutionMode(DocumentParsingProperties.IN_PROCESS);
        return properties;
    }

    /** Spring 优雅停止时终止在途解析；租约恢复不依赖解析线程响应中断。 */
    @Override public void close() { if (isolated != null) isolated.close(); }

    /** 每次解析独享有界 handler，避免不同请求共享收集缓冲区或静默截断。 */
    @Autowired
    public DocumentTextExtractor(DocumentParsingProperties properties) {
        int limit = properties.getMaxTextChars();
        isolated = DocumentParsingProperties.FORKED.equals(properties.getExecutionMode())
                ? new ForkedDocumentParser(properties) : null;
        parser = isolated != null ? null : new ApacheTikaDocumentParser(null,
                () -> new org.apache.tika.sax.ContentHandlerDecorator(new BodyContentHandler(limit)) {
                    private int pages;
                    @Override
                    public void startElement(String uri, String local, String name, org.xml.sax.Attributes attributes)
                            throws org.xml.sax.SAXException {
                        if ("div".equals(local) && "page".equals(attributes.getValue("class"))
                                && ++pages > properties.getMaxPages()) {
                            throw new org.xml.sax.SAXException(new DocumentParsingException(DocumentParsingError.PAGE_LIMIT, null));
                        }
                        super.startElement(uri, local, name, attributes);
                    }
                }, null, DocumentTextExtractor::parseContext, false);
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
        if (isolated != null) return isolated.extract(in);
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
            if (cause instanceof DocumentParsingException parsed) return parsed.error();
            if (cause instanceof WriteLimitReachedException) return DocumentParsingError.TEXT_LIMIT;
            if (cause instanceof SourceReadException) return DocumentParsingError.SOURCE_READ;
            if (cause instanceof EncryptedDocumentException) return DocumentParsingError.ENCRYPTED;
            if (cause instanceof BlankDocumentException) return DocumentParsingError.EMPTY;
        }
        return DocumentParsingError.INVALID;
    }

    private static org.apache.tika.parser.ParseContext parseContext() {
        var context = new org.apache.tika.parser.ParseContext();
        var pdf = new org.apache.tika.parser.pdf.PDFParserConfig();
        pdf.setOcrStrategy(org.apache.tika.parser.pdf.PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdf.setExtractInlineImages(false);
        context.set(org.apache.tika.parser.pdf.PDFParserConfig.class, pdf);
        var ocr = new org.apache.tika.parser.ocr.TesseractOCRConfig();
        ocr.setSkipOcr(true);
        context.set(org.apache.tika.parser.ocr.TesseractOCRConfig.class, ocr);
        // 嵌入附件不能隐式扩成另一棵解析任务；后续结构结果将携带跳过告警。
        context.set(org.apache.tika.extractor.EmbeddedDocumentExtractor.class, new org.apache.tika.extractor.EmbeddedDocumentExtractor() {
            @Override public boolean shouldParseEmbedded(org.apache.tika.metadata.Metadata metadata) { return false; }
            @Override public void parseEmbedded(InputStream input, org.xml.sax.ContentHandler handler,
                                               org.apache.tika.metadata.Metadata metadata, boolean outputHtml) { }
        });
        return context;
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
