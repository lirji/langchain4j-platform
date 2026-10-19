package com.lrj.platform.knowledge.lifecycle;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** 进程间传输的结构结果：只含不可变值，定位来自原文件，告警不能被“正文非空”掩盖。 */
public record ParsedDocument(int schemaVersion, String format, List<Block> blocks, List<Warning> warnings) {
    public static final int SCHEMA_VERSION = 1;
    public static final String TEXT = "text", CODE = "code", TABLE = "table", PAGE = "page";
    public static final String SOURCE_TEXT = "source_text", CONTEXT_PREFIX = "context_prefix";
    public static final String SOURCE_LOCATION = "source_location", WARNINGS = "parsing_warnings";

    public ParsedDocument {
        blocks = List.copyOf(blocks); warnings = List.copyOf(warnings);
        if (schemaVersion != SCHEMA_VERSION || format == null || format.isBlank()) throw invalid();
    }

    /** 原文视图用于文档窗口；表格用保留空单元格/换行的 CSV 表示，不混入模型生成内容。 */
    public String text() { return blocks.stream().map(Block::sourceText).filter(s -> !s.isBlank())
            .collect(java.util.stream.Collectors.joining("\n\n")); }

    /** 兼容已有 JSON 纯正文入口，未伪造页号或行号。 */
    public static ParsedDocument plain(String text) {
        return new ParsedDocument(SCHEMA_VERSION, "text", List.of(new Block(TEXT, text, Location.none(), List.of(), List.of())), List.of());
    }

    /** 块数与总文本预算在子进程和父进程都验证，不能信任传输 JSON 的规模声明。 */
    public ParsedDocument validate(DocumentParsingProperties limits) {
        long chars = 0, units = 0;
        for (Block block : blocks) {
            units += 1L + block.rows().size();
            chars += block.text().length();
            for (Row row : block.rows()) {
                units += row.cells().size();
                for (String cell : row.cells()) chars += cell.length();
            }
            if (units > limits.getMaxStructureUnits()) throw new DocumentParsingException(DocumentParsingError.STRUCTURE_LIMIT, null);
            if (chars > limits.getMaxTextChars()) throw new DocumentParsingException(DocumentParsingError.TEXT_LIMIT, null);
        }
        if (warnings.size() > limits.getMaxStructureUnits()) throw invalid();
        String rendered = text();
        if (rendered.length() > limits.getMaxTextChars()) throw new DocumentParsingException(DocumentParsingError.TEXT_LIMIT, null);
        if (rendered.isBlank()) throw new DocumentParsingException(DocumentParsingError.EMPTY, null);
        return this;
    }

    /** 表格保存原始单元格而非用分隔符猜回结构；空行/空列也是位置证据。 */
    public record Block(String kind, String text, Location location, List<String> headings, List<Row> rows) {
        public Block {
            if (!List.of(TEXT, CODE, TABLE, PAGE).contains(kind) || text == null || location == null) throw invalid();
            headings = List.copyOf(headings); rows = List.copyOf(rows);
        }
        public String sourceText() {
            if (!TABLE.equals(kind)) return text;
            StringBuilder out = new StringBuilder();
            if (location.sheet() != null) out.append(location.sheet()).append('\n');
            for (Row row : rows) out.append(csv(row.cells())).append('\n');
            return out.toString();
        }
    }
    public record Row(int number, List<String> cells) {
        public Row { if (number < 1) throw invalid(); cells = List.copyOf(cells); }
    }
    /** 仅声明能够回查的定位，PDF 不伪造 bbox，CSV 行号指逻辑记录而非包含引号换行的物理行。 */
    public record Location(Integer page, Integer rotation, String sheet, Integer paragraph, Integer table,
                           Integer rowStart, Integer rowEnd, Integer lineStart, Integer lineEnd) {
        public static Location none() { return new Location(null, null, null, null, null, null, null, null, null); }
        public Map<String, String> metadata() {
            Map<String, String> values = new LinkedHashMap<>();
            put(values, "source_page", page); put(values, "source_rotation", rotation); put(values, "source_sheet", sheet);
            put(values, "source_paragraph", paragraph); put(values, "source_table", table);
            put(values, "source_row_start", rowStart); put(values, "source_row_end", rowEnd);
            put(values, "source_line_start", lineStart); put(values, "source_line_end", lineEnd);
            if (!values.isEmpty()) values.put(SOURCE_LOCATION, values.entrySet().stream()
                    .map(e -> e.getKey().substring(7) + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(";")));
            return values;
        }
        private static void put(Map<String, String> map, String key, Object value) { if (value != null) map.put(key, value.toString()); }
    }
    /** code 是稳定机器值，位置明确说明缺失或保守处理发生在哪里。 */
    public record Warning(String code, Location location) {
        public Warning { if (code == null || code.isBlank() || location == null) throw invalid(); }
    }
    /** RFC 风格转义保留含逗号/双引号/换行的单元格，不丢失空列。 */
    public static String csv(List<String> cells) {
        return cells.stream().map(value -> value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
                ? "\"" + value.replace("\"", "\"\"") + "\"" : value).collect(java.util.stream.Collectors.joining(","));
    }
    private static DocumentParsingException invalid() { return new DocumentParsingException(DocumentParsingError.PROCESS_FAILED, null); }
}
