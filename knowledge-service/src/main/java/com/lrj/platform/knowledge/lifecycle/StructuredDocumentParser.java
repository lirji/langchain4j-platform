package com.lrj.platform.knowledge.lifecycle;

import org.apache.commons.csv.CSVFormat;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.tika.Tika;
import org.apache.tika.detect.AutoDetectReader;
import org.commonmark.node.Heading;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.parser.IncludeSourceSpans;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.lrj.platform.knowledge.lifecycle.ParsedDocument.*;

/** 专用格式解析保留原文结构；只在隔离进程或显式离线测试使用，未知格式沿用受限 Tika。 */
final class StructuredDocumentParser {
    private final DocumentParsingProperties limits;
    private final List<Block> blocks = new ArrayList<>();
    private final List<Warning> warnings = new ArrayList<>();
    private long chars, units;

    StructuredDocumentParser(DocumentParsingProperties limits) { this.limits = limits; }

    ParsedDocument parse(Path input, String filename) {
        try {
            String type = new Tika().detect(input);
            String suffix = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
            String format;
            if ("application/pdf".equals(type)) { format = "pdf"; pdf(input); }
            else if (type.contains("wordprocessingml")) { format = "docx"; word(input); }
            else if (type.contains("spreadsheetml")) { format = "xlsx"; spreadsheet(input); }
            else if ("text/csv".equals(type) || (type.startsWith("text/") && suffix.endsWith(".csv"))) {
                format = "csv"; csvFile(input);
            } else if (type.startsWith("text/") && (suffix.endsWith(".md") || suffix.endsWith(".markdown"))) {
                format = "markdown"; markdown(readText(input));
            } else if ("text/plain".equals(type)) {
                format = "text"; String text = readText(input);
                add(new Block(TEXT, text, new Location(null,null,null,null,null,null,null,1,lineCount(text)),List.of(),List.of()));
            } else {
                format = "tika";
                try (var source = Files.newInputStream(input)) {
                    var properties = new DocumentParsingProperties();
                    properties.setExecutionMode(DocumentParsingProperties.IN_PROCESS);
                    properties.setMaxTextChars(limits.getMaxTextChars()); properties.setMaxPages(limits.getMaxPages());
                    add(new Block(TEXT, new DocumentTextExtractor(properties).extract(source, "fallback-document"),
                            Location.none(), List.of(), List.of()));
                }
                warn(ParsingWarnings.STRUCTURE_UNAVAILABLE, Location.none());
                warn(ParsingWarnings.EMBEDDED_SKIPPED, Location.none());
            }
            return new ParsedDocument(SCHEMA_VERSION, format, blocks, warnings).validate(limits);
        } catch (DocumentParsingException failure) { throw failure; }
        catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException | org.apache.poi.EncryptedDocumentException failure) {
            throw new DocumentParsingException(DocumentParsingError.ENCRYPTED, failure);
        } catch (Exception failure) { throw new DocumentParsingException(DocumentParsingError.INVALID, failure); }
    }

    private String readText(Path path) throws Exception {
        try (var source = Files.newInputStream(path); var reader = new AutoDetectReader(source)) {
            var text = new StringBuilder(); char[] buffer = new char[8192]; int count;
            while ((count = reader.read(buffer)) >= 0) {
                if ((long) text.length() + count > limits.getMaxTextChars()) fail(DocumentParsingError.TEXT_LIMIT);
                text.append(buffer, 0, count);
            }
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text.deleteCharAt(0);
            return text.toString();
        }
    }

    private void csvFile(Path path) throws Exception {
        List<Row> rows = new ArrayList<>();
        try (var source = Files.newInputStream(path); var reader = new AutoDetectReader(source);
             var parser = CSVFormat.RFC4180.builder().setIgnoreEmptyLines(false).get().parse(reader)) {
            for (var record : parser) {
                List<String> cells = new ArrayList<>();
                for (String cell : record) cells.add(cell);
                if (record.getRecordNumber() == 1 && !cells.isEmpty()) cells.set(0, cells.getFirst().replaceFirst("^\uFEFF", ""));
                addRow(rows, new Row(Math.toIntExact(record.getRecordNumber()), cells));
            }
        }
        addTable(rows, new Location(null,null,null,null,1,null,null,null,null), List.of());
    }

    private void spreadsheet(Path path) throws Exception {
        try (var book = WorkbookFactory.create(path.toFile(), null, true)) {
            var formatter = new DataFormatter(Locale.ROOT); formatter.setUseCachedValuesForFormulaCells(true);
            for (int sheetIndex = 0; sheetIndex < book.getNumberOfSheets(); sheetIndex++) {
                var sheet = book.getSheetAt(sheetIndex);
                var location = new Location(null,null,sheet.getSheetName(),null,sheetIndex + 1,null,null,null,null);
                if (sheet.getNumMergedRegions() > 0) warn(ParsingWarnings.MERGED_CELLS, location);
                boolean hidden = book.isSheetHidden(sheetIndex) || book.isSheetVeryHidden(sheetIndex), formula = false;
                List<Row> rows = new ArrayList<>();
                for (var row : sheet) {
                    hidden |= row.getZeroHeight();
                    List<String> cells = new ArrayList<>();
                    int last = Math.max(0, row.getLastCellNum());
                    if (last > limits.getMaxStructureUnits()) fail(DocumentParsingError.STRUCTURE_LIMIT);
                    for (int column = 0; column < last; column++) {
                        var cell = row.getCell(column);
                        formula |= cell != null && cell.getCellType() == CellType.FORMULA;
                        // 不调用 FormulaEvaluator，避免外部链接/任意公式执行，并保留格式化前导零和日期。
                        cells.add(cell == null ? "" : formatter.formatCellValue(cell));
                    }
                    addRow(rows, new Row(row.getRowNum() + 1, cells));
                }
                if (hidden) warn(ParsingWarnings.HIDDEN_CONTENT, location);
                if (formula) warn(ParsingWarnings.FORMULA_CACHE, location);
                addTable(rows, location, List.of());
            }
        }
    }

    private void word(Path path) throws Exception {
        try (var source = Files.newInputStream(path); var document = new XWPFDocument(source)) {
            List<String> headings = new ArrayList<>(); int paragraph = 0, table = 0;
            for (var body : document.getBodyElements()) {
                if (body instanceof XWPFParagraph para) {
                    paragraph++;
                    String text = para.getText();
                    var paragraphLocation = new Location(null,null,null,paragraph,null,null,null,null,null);
                    if (para.getCTP().sizeOfInsArray() > 0 || para.getCTP().sizeOfDelArray() > 0)
                        warn(ParsingWarnings.WORD_REVISIONS, paragraphLocation);
                    if (para.getNumID() != null) warn(ParsingWarnings.WORD_LIST_LABELS, paragraphLocation);
                    int level = headingLevel(para, document);
                    if (level > 0) {
                        while (headings.size() >= level) headings.removeLast();
                        while (headings.size() < level - 1) headings.add("");
                        headings.add(text);
                    }
                    if (!text.isBlank()) add(new Block(TEXT, text,
                            paragraphLocation,
                            headings.stream().filter(s -> !s.isBlank()).toList(),List.of()));
                } else if (body instanceof XWPFTable grid) {
                    List<Row> rows = new ArrayList<>(); int index = 0;
                    for (var row : grid.getRows()) addRow(rows, new Row(++index,
                            row.getTableCells().stream().map(cell -> cell.getTextRecursively()).toList()));
                    addTable(rows, new Location(null,null,null,null,++table,null,null,null,null), headings);
                }
            }
            if (!document.getHeaderList().isEmpty() || !document.getFooterList().isEmpty()
                    || !document.getFootnotes().isEmpty() || !document.getEndnotes().isEmpty()
                    || !document.getAllPictures().isEmpty() || (document.getComments() != null && document.getComments().length > 0)) {
                warn(ParsingWarnings.WORD_AUXILIARY, Location.none());
            }
            if (!document.getAllEmbeddedParts().isEmpty()) warn(ParsingWarnings.EMBEDDED_SKIPPED, Location.none());
        }
    }

    /** Word 的 styleId 可以是数字或自定义名称，优先读大纲级别，再追踪有界的样式继承链。 */
    private static int headingLevel(XWPFParagraph paragraph, XWPFDocument document) {
        var direct = paragraph.getCTP().getPPr();
        if (direct != null && direct.isSetOutlineLvl()) {
            int level = direct.getOutlineLvl().getVal().intValue();
            if (level >= 0 && level < 9) return level + 1;
        }
        String id = paragraph.getStyle(); var visited = new java.util.HashSet<String>();
        while (id != null && visited.size() < 16 && visited.add(id)) {
            if (id.matches("(?i)(heading|标题)\\s*[1-9]")) return Character.digit(id.charAt(id.length()-1),10);
            var style = document.getStyles() == null ? null : document.getStyles().getStyle(id);
            if (style == null) break;
            var properties = style.getCTStyle().getPPr();
            if (properties != null && properties.isSetOutlineLvl()) {
                int level = properties.getOutlineLvl().getVal().intValue();
                if (level >= 0 && level < 9) return level + 1;
            }
            String name = style.getName();
            if (name != null && name.matches("(?i)(heading|标题)\\s*[1-9]")) return Character.digit(name.charAt(name.length()-1),10);
            id = style.getBasisStyleID();
        }
        return 0;
    }

    private void markdown(String text) {
        Node root = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build().parse(text);
        List<String> headings = new ArrayList<>();
        for (Node node = root.getFirstChild(); node != null; node = node.getNext()) {
            if (node.getSourceSpans().isEmpty()) continue;
            var first = node.getSourceSpans().getFirst();
            int start = first.getInputIndex() - first.getColumnIndex();
            int end = node.getNext() == null ? text.length() : node.getNext().getSourceSpans().getFirst().getInputIndex()
                    - node.getNext().getSourceSpans().getFirst().getColumnIndex();
            String raw = text.substring(start,end);
            if (node instanceof Heading heading) {
                StringBuilder title = new StringBuilder();
                heading.accept(new org.commonmark.node.AbstractVisitor() {
                    @Override public void visit(org.commonmark.node.Text value) { title.append(value.getLiteral()); }
                    @Override public void visit(org.commonmark.node.Code value) { title.append(value.getLiteral()); }
                });
                while (headings.size() >= heading.getLevel()) headings.removeLast();
                while (headings.size() < heading.getLevel() - 1) headings.add("");
                headings.add(title.toString());
            }
            add(new Block(node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock ? CODE : TEXT, raw,
                    new Location(null,null,null,null,null,null,null,first.getLineIndex()+1,first.getLineIndex()+lineCount(raw)),
                    headings.stream().filter(s -> !s.isBlank()).toList(),List.of()));
        }
    }

    private void pdf(Path path) throws Exception {
        try (var document = Loader.loadPDF(path.toFile())) {
            if (document.getNumberOfPages() > limits.getMaxPages()) fail(DocumentParsingError.PAGE_LIMIT);
            warn(ParsingWarnings.PDF_READING_ORDER, Location.none());
            if (document.getDocumentCatalog().getNames() != null && document.getDocumentCatalog().getNames().getEmbeddedFiles() != null)
                warn(ParsingWarnings.EMBEDDED_SKIPPED, Location.none());
            var pageText = new StringBuilder();
            var stripper = new PDFTextStripper() {
                @Override protected void startPage(PDPage page) throws IOException { pageText.setLength(0); super.startPage(page); }
                @Override protected void endPage(PDPage page) throws IOException {
                    var location = new Location(getCurrentPageNo(),page.getRotation(),null,null,null,null,null,null,null);
                    if (pageText.toString().isBlank()) warn(ParsingWarnings.PDF_NO_TEXT, location);
                    if (page.getRotation() != 0) warn(ParsingWarnings.PDF_ROTATED, location);
                    // 写入时已计字符预算，这里只加块数，避免重复统计。
                    reserve(0,1); blocks.add(new Block(PAGE,pageText.toString(),location,List.of(),List.of()));
                    super.endPage(page);
                }
            };
            stripper.setSortByPosition(true);
            stripper.writeText(document, new Writer() {
                @Override public void write(char[] data, int offset, int length) { reserve(length,0); pageText.append(data,offset,length); }
                @Override public void flush() { }
                @Override public void close() { }
            });
            if (limits.getOcr().isEnabled()) {
                var processed = new PdfPageOcr(limits,new TesseractOcrEngine(limits.getOcr())).apply(document,blocks,
                        warning -> warn(warning.code(),warning.location()));
                long oldChars=blocks.stream().mapToLong(b -> b.text().length()).sum();
                reserve(processed.stream().mapToLong(b -> b.text().length()).sum()-oldChars,0);
                blocks.clear();blocks.addAll(processed);
                // 已补齐文字的页不再保留“无文本”告警；真实空白页仍保留覆盖事实。
                warnings.removeIf(w -> ParsingWarnings.PDF_NO_TEXT.equals(w.code()) && w.location().page()!=null
                        && !blocks.get(w.location().page()-1).text().isBlank());
            }
        }
    }

    private void add(Block block) { reserve(block.text().length(),1); blocks.add(block); }
    private void addRow(List<Row> rows, Row row) {
        reserve(row.cells().stream().mapToLong(String::length).sum(), 1L + row.cells().size()); rows.add(row);
    }
    private void addTable(List<Row> rows, Location location, List<String> headings) {
        if (rows.stream().allMatch(row -> row.cells().stream().allMatch(String::isBlank))) return;
        reserve(0,1); blocks.add(new Block(TABLE,"",location,headings,rows)); warn(ParsingWarnings.HEADER_ASSUMED,location);
    }
    private void warn(String code, Location location) {
        if (warnings.size() >= limits.getMaxStructureUnits()) fail(DocumentParsingError.STRUCTURE_LIMIT);
        warnings.add(new Warning(code,location));
    }
    private void reserve(long moreChars, long moreUnits) {
        chars += moreChars; units += moreUnits;
        if (chars > limits.getMaxTextChars()) fail(DocumentParsingError.TEXT_LIMIT);
        if (units > limits.getMaxStructureUnits()) fail(DocumentParsingError.STRUCTURE_LIMIT);
    }
    private static int lineCount(String text) {
        return Math.max(1, (int) text.lines().count());
    }
    private static void fail(DocumentParsingError error) { throw new DocumentParsingException(error,null); }
}
