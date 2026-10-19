package com.lrj.platform.knowledge.lifecycle;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 自包含、无业务敏感信息的真实格式语料；逻辑内容固定，二进制容器时间戳不作为质量断言。 */
final class DocumentParsingCorpus {
    static final String VERSION = "kdp-corpus-v1";
    record Sample(String id, String filename, byte[] bytes, List<String> expected, String expectedError,
                  List<String> rowMarkers, List<String> headers, String locatorKind) { }

    static List<Sample> samples() throws Exception {
        var samples = new ArrayList<Sample>();
        samples.add(text("txt-bilingual", "notes.txt", "部署顺序：先迁移数据库，再启动 worker。\nVersion 1.2.3; see https://example.test/docs.\n保留编号 00123。",
                List.of("先迁移数据库", "Version 1.2.3", "00123"), List.of(), List.of(), "line"));
        samples.add(text("txt-long", "long.txt", "Technical deployment paragraph. Safe retry and rollback.\n".repeat(1500) + "FINAL_MARKER",
                List.of("Technical deployment paragraph", "FINAL_MARKER"), List.of(), List.of(), "line"));
        samples.add(text("md-technical", "guide.md", "# 部署指南\r\n## 准备\r\n执行迁移。\r\n```bash\r\n## 代码中的注释\r\necho \"version 1.2.3\"\r\n```\r\n## 恢复\r\n- 保留任务\r\n- 检查日志\r\n文档链接 https://example.test/docs\r\n附录\r\n====\r\nEND_MARKER\r\n",
                List.of("代码中的注释", "version 1.2.3", "保留任务", "END_MARKER"), List.of(), List.of(), "line"));
        samples.add(text("md-long-sections", "long.md", "# 手册\n" + java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> "## Chapter " + index + "\n" + ("SECTION_" + index + " 配置与故障恢复说明。\n").repeat(30))
                .collect(java.util.stream.Collectors.joining()), List.of("SECTION_0", "SECTION_19"), List.of(), List.of(), "line"));
        samples.add(text("csv-quoted", "quoted.csv", "\uFEFF编号,名称,数量\r\n00123,\"中文,手册\",42\r\n00124,\"多行\n说明\",7\r\n",
                List.of("00123", "中文", "手册", "00124", "多行", "说明"), List.of("00123", "00124"), List.of("编号", "名称", "数量"), "row"));
        var rows = new ArrayList<String>();
        var csv = new StringBuilder("编号,名称,数量\n");
        for (int row = 1; row <= 100; row++) {
            String marker = "ITEM_" + String.format(java.util.Locale.ROOT, "%04d", row);
            rows.add(marker); csv.append(marker).append(",部署手册,42\n");
        }
        samples.add(text("csv-long-rows", "rows.csv", csv.toString(), rows, rows, List.of("编号", "名称", "数量"), "row"));
        samples.add(new Sample("xlsx-two-sheets", "sheets.xlsx", workbook(false), List.of("第一表", "第二表", "00123", "中文手册", "00456"),
                null, List.of("00123", "00456"), List.of("编号", "名称", "数量"), "sheet/row/cell"));
        samples.add(new Sample("xlsx-long-rows", "rows.xlsx", workbook(true), rows,
                null, rows, List.of("编号", "名称", "数量"), "sheet/row/cell"));
        samples.add(new Sample("docx-headings-table", "guide.docx", word(false), List.of("部署指南", "回滚步骤", "SKU_001", "数量"),
                null, List.of("SKU_001"), List.of("编号", "名称", "数量"), "paragraph/table/row"));
        samples.add(new Sample("docx-long", "long.docx", word(true), List.of("部署指南", "PARAGRAPH_199", "回滚步骤", "SKU_001"),
                null, List.of("SKU_001"), List.of("编号", "名称", "数量"), "paragraph/table/row"));
        samples.add(new Sample("pdf-two-pages", "manual.pdf", pdf(2, false, false), List.of("PAGE_1", "PAGE_2", "Technical deployment manual"),
                null, List.of(), List.of(), "page"));
        samples.add(new Sample("pdf-long", "manual.pdf", pdf(30, false, false), List.of("PAGE_1", "PAGE_30"),
                null, List.of(), List.of(), "page"));
        samples.add(new Sample("pdf-mixed-image", "mixed.pdf", pdf(2, false, true), List.of("PAGE_1"),
                null, List.of(), List.of(), "page-coverage-warning"));
        samples.add(new Sample("pdf-encrypted", "private.pdf", pdf(1, true, false), List.of(),
                DocumentParsingError.ENCRYPTED.code(), List.of(), List.of(), "none"));
        samples.add(new Sample("pdf-corrupt", "broken.pdf", "%PDF-1.7\ninvalid\n%%EOF".getBytes(StandardCharsets.UTF_8), List.of(),
                DocumentParsingError.INVALID.code(), List.of(), List.of(), "none"));
        samples.add(new Sample("empty", "empty.txt", new byte[0], List.of(), DocumentParsingError.EMPTY.code(), List.of(), List.of(), "none"));
        samples.add(text("text-over-budget", "huge.txt", "x".repeat(DocumentParsingProperties.DEFAULT_MAX_TEXT_CHARS + 1),
                List.of(), List.of(), List.of(), "none", DocumentParsingError.TEXT_LIMIT.code()));
        return samples;
    }

    private static Sample text(String id, String name, String content, List<String> expected,
                               List<String> rows, List<String> headers, String locator) {
        return text(id, name, content, expected, rows, headers, locator, null);
    }
    private static Sample text(String id, String name, String content, List<String> expected,
                               List<String> rows, List<String> headers, String locator, String error) {
        return new Sample(id, name, content.getBytes(StandardCharsets.UTF_8), expected, error, rows, headers, locator);
    }
    private static byte[] workbook(boolean longRows) throws Exception {
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            for (int sheetIndex = 0; sheetIndex < (longRows ? 1 : 2); sheetIndex++) {
                var sheet = book.createSheet(sheetIndex == 0 ? "第一表" : "第二表");
                var header = sheet.createRow(0);
                for (int col = 0; col < 3; col++) header.createCell(col).setCellValue(List.of("编号", "名称", "数量").get(col));
                for (int index = 1; index <= (longRows ? 100 : 1); index++) {
                    var row = sheet.createRow(index);
                    row.createCell(0).setCellValue(longRows ? "ITEM_" + String.format(java.util.Locale.ROOT, "%04d", index)
                            : sheetIndex == 0 ? "00123" : "00456");
                    row.createCell(1).setCellValue("中文手册");
                    row.createCell(2).setCellValue(42);
                }
            }
            book.write(output); return output.toByteArray();
        }
    }
    private static byte[] word(boolean longDocument) throws Exception {
        try (var doc = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            var heading = doc.createParagraph(); heading.setStyle("Heading1"); heading.createRun().setText("部署指南");
            for (int index = 0; index < (longDocument ? 200 : 1); index++) {
                doc.createParagraph().createRun().setText("PARAGRAPH_" + index + " 先迁移数据库，再启动服务。Keep the original version for rollback.");
            }
            var section = doc.createParagraph(); section.setStyle("Heading2"); section.createRun().setText("回滚步骤");
            var table = doc.createTable(2, 3);
            for (int col = 0; col < 3; col++) table.getRow(0).getCell(col).setText(List.of("编号", "名称", "数量").get(col));
            table.getRow(1).getCell(0).setText("SKU_001"); table.getRow(1).getCell(1).setText("手册"); table.getRow(1).getCell(2).setText("42");
            doc.write(output); return output.toByteArray();
        }
    }
    private static byte[] pdf(int pages, boolean encrypted, boolean imageLast) throws Exception {
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int index = 1; index <= pages; index++) {
                var page = new PDPage(); doc.addPage(page);
                try (var content = new PDPageContentStream(doc, page)) {
                    if (imageLast && index == pages) {
                        // 有图片但无文本层，基线只观察漏页告警能力；不伪装为 OCR 成功样本。
                        var image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
                        content.drawImage(LosslessFactory.createFromImage(doc, image), 40, 600, 100, 100);
                    } else {
                        content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(40, 700);
                        content.showText("PAGE_" + index + " Technical deployment manual. Quantity 42."); content.endText();
                    }
                }
            }
            if (encrypted) doc.protect(new StandardProtectionPolicy("fixture-owner", "fixture-reader", new AccessPermission()));
            doc.save(output); return output.toByteArray();
        }
    }
}
