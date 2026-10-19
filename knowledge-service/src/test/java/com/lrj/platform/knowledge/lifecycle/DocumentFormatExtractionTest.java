package com.lrj.platform.knowledge.lifecycle;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用真实格式的小样本验证 Tika 链路；不依赖模型、外部文件、OCR 或网络。 */
class DocumentFormatExtractionTest {
    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void extractsRealPdfText() throws Exception {
        assertThat(extract(pdf(false), "manual.pdf")).contains("Quantity 42");
    }

    @Test
    void encryptedPdfIsPermanentAndHasASafeError() throws Exception {
        byte[] document = pdf(true);
        assertThatThrownBy(() -> extract(document, "private.pdf"))
                .isInstanceOfSatisfying(DocumentParsingException.class, ex -> {
                    assertThat(ex.error()).isEqualTo(DocumentParsingError.ENCRYPTED);
                    assertThat(ex.error().retryable()).isFalse();
                    assertThat(ex.getMessage()).doesNotContain("private", "fixture-password");
                });
    }

    @Test
    void malformedPdfIsNotMistakenForATemporaryReadFailure() {
        assertThatThrownBy(() -> extract("%PDF-1.7\ninvalid file\n%%EOF".getBytes(StandardCharsets.UTF_8), "broken.pdf"))
                .isInstanceOfSatisfying(DocumentParsingException.class,
                        ex -> assertThat(ex.error()).isEqualTo(DocumentParsingError.INVALID));
    }

    @Test
    void extractsChineseDocxParagraphAndTable() throws Exception {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("部署指南：启动知识库服务。");
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("项目");
            table.getRow(0).getCell(1).setText("数量");
            table.getRow(1).getCell(0).setText("说明书");
            table.getRow(1).getCell(1).setText("42");
            document.write(output);
            assertThat(extract(output.toByteArray(), "manual.docx"))
                    .contains("部署指南", "知识库服务", "项目", "数量", "说明书", "42");
        }
    }

    @Test
    void extractsXlsxHeadersChineseValuesAndLeadingZeroString() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("产品");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("编号");
            header.createCell(1).setCellValue("名称");
            header.createCell(2).setCellValue("数量");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("00123");
            row.createCell(1).setCellValue("中文手册");
            row.createCell(2).setCellValue(42);
            workbook.write(output);
            assertThat(extract(output.toByteArray(), "products.xlsx"))
                    .contains("编号", "名称", "数量", "00123", "中文手册", "42");
        }
    }

    @Test
    void extractsUtf8CsvWithQuotedChineseValues() throws Exception {
        String csv = "编号,名称,数量\r\n00123,\"中文,手册\",42\r\n";
        assertThat(extract(csv.getBytes(StandardCharsets.UTF_8), "products.csv"))
                .contains("编号", "名称", "数量", "00123", "中文", "手册", "42");
    }

    private String extract(byte[] bytes, String name) throws Exception {
        try (var input = new ByteArrayInputStream(bytes)) { return extractor.extract(input, name); }
    }

    private static byte[] pdf(boolean encrypted) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText("Quantity 42");
                content.endText();
            }
            if (encrypted) document.protect(new StandardProtectionPolicy("fixture-owner", "fixture-password", new AccessPermission()));
            document.save(output);
            return output.toByteArray();
        }
    }
}
