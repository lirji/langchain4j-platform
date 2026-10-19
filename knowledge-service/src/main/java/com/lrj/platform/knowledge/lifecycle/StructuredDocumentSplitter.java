package com.lrj.platform.knowledge.lifecycle;

import com.lrj.platform.knowledge.ChunkIndexing;
import com.lrj.platform.knowledge.ParentChildSplitter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.lrj.platform.knowledge.lifecycle.ParsedDocument.*;

/** 结构先限定边界，既有策略仅在块内工作；表格重复表头并携带行列定位，所有最终尺寸再次校验。 */
public final class StructuredDocumentSplitter {
    private final int maxSize, maxChunks;
    private final DocumentSplitter fallback;
    private final TokenCountEstimator estimator;

    public StructuredDocumentSplitter(int maxSize, int maxChunks, DocumentSplitter fallback, TokenCountEstimator estimator) {
        this.maxSize = maxSize; this.maxChunks = maxChunks; this.fallback = fallback; this.estimator = estimator;
    }

    /** 原始结构不含租户身份；由已认证的入库层提供 metadata，源文件不能覆盖 tenant/doc/version。 */
    public List<TextSegment> split(ParsedDocument parsed, Metadata base) {
        List<TextSegment> out = new ArrayList<>(); int blockIndex = 0;
        for (Block block : parsed.blocks()) {
            Metadata metadata = base.copy();
            metadata.put("source_format", parsed.format()); metadata.put("source_block", Integer.toString(blockIndex++));
            metadata.put("source_kind", block.kind()); block.location().metadata().forEach(metadata::put);
            if (!block.headings().isEmpty()) {
                metadata.put("section", block.headings().getLast()); metadata.put("breadcrumb", String.join(" > ", block.headings()));
            }
            if (!parsed.warnings().isEmpty()) {
                metadata.put(WARNINGS, parsed.warnings().stream().map(Warning::code).distinct().sorted().collect(Collectors.joining(",")));
                String missing = parsed.warnings().stream().filter(w -> ParsingWarnings.PDF_NO_TEXT.equals(w.code()))
                        .map(w -> String.valueOf(w.location().page())).collect(Collectors.joining(","));
                if (!missing.isEmpty()) metadata.put("source_pages_without_text", missing);
            }
            if (TABLE.equals(block.kind())) table(block,metadata,out);
            else if (CODE.equals(block.kind())) {
                // 代码不可经递归 splitter 的 trim/空白规整；超预算时逐字符片段保留缩进与换行。
                if (size(block.text()) <= maxSize) add(out,block.text(),metadata);
                else {
                    int part = 0;
                    for (String fragment : fragments("",block.text())) {
                        var values = metadata.copy(); values.put("source_code_fragment",Integer.toString(++part));
                        add(out,fragment,values);
                    }
                }
            }
            else if (!block.text().isBlank()) {
                List<TextSegment> chunks = fallback.split(Document.from(block.text(),metadata));
                for (TextSegment chunk : chunks) {
                    var values = chunk.metadata().copy();
                    String parent = values.getString(ParentChildSplitter.PARENT_ID);
                    if (parent != null) values.put(ParentChildSplitter.PARENT_ID, metadata.getString("source_block") + ":" + parent);
                    // 语义策略等兜底也必须遵守最终模型输入尺寸，不能因上层策略返回超大段而绕过预算。
                    if (size(chunk.text()) <= maxSize) add(out,chunk.text(),values);
                    else for (String part : fragments("",chunk.text())) add(out,part,values);
                }
            }
        }
        return ChunkIndexing.sequential(out);
    }

    private void table(Block block, Metadata base, List<TextSegment> out) {
        List<Row> rows = block.rows();
        Row header = rows.stream().filter(row -> row.cells().stream().anyMatch(cell -> !cell.isBlank())).findFirst().orElse(null);
        if (header == null) return;
        int width = rows.stream().mapToInt(row -> row.cells().size()).max().orElse(0);
        List<List<Integer>> groups = new ArrayList<>();
        List<Integer> group = new ArrayList<>(); group.add(0);
        for (int column = 1; column < width; column++) {
            List<Integer> candidate = new ArrayList<>(group); candidate.add(column);
            // 宽表列组保留首列便于识别记录；列标题预算留出正文空间。
            if (size(render(header,candidate) + "\n") > maxSize / 2 && group.size() > 1) {
                groups.add(List.copyOf(group)); group = new ArrayList<>(List.of(0));
            }
            group.add(column);
        }
        groups.add(List.copyOf(group));
        for (List<Integer> columns : groups) {
            String prefix = render(header,columns) + "\n";
            if (size(prefix) >= maxSize) fail(); // 单个超长字段名也不能静默丢弃或截断。
            List<Row> pending = new ArrayList<>(); StringBuilder body = new StringBuilder();
            for (Row row : rows) {
                if (row.number() <= header.number()) continue;
                String raw = render(row,columns) + "\n";
                if (!body.isEmpty() && size(prefix + body + raw) > maxSize) {
                    tableChunk(out,prefix + body,base,pending.getFirst().number(),pending.getLast().number(),columns,0);
                    pending.clear(); body.setLength(0);
                }
                if (size(prefix + raw) > maxSize) {
                    // 长行先按列收窄，避免在不同单元格之间盲切，把字段标识拆成无法检索的两半。
                    for (Integer column : columns) {
                        List<Integer> cellColumns = column == 0 ? List.of(0) : List.of(0,column);
                        String cellHeader = render(header,cellColumns) + "\n";
                        String cellValue = render(row,cellColumns) + "\n";
                        if (size(cellHeader) >= maxSize) fail();
                        if (size(cellHeader + cellValue) <= maxSize)
                            tableChunk(out,cellHeader + cellValue,base,row.number(),row.number(),cellColumns,0);
                        else {
                            // 单个超长单元格最终分段，保留行/列/片段定位，不声称每片都是完整 CSV 记录。
                            int part = 0;
                            for (String fragment : fragments(cellHeader,cellValue))
                                tableChunk(out,fragment,base,row.number(),row.number(),cellColumns,++part);
                        }
                    }
                } else { pending.add(row); body.append(raw); }
            }
            if (!body.isEmpty()) tableChunk(out,prefix + body,base,pending.getFirst().number(),pending.getLast().number(),columns,0);
            else if (rows.stream().noneMatch(row -> row.number() > header.number()))
                tableChunk(out,prefix,base,header.number(),header.number(),columns,0);
        }
    }

    private String render(Row row, List<Integer> columns) {
        return csv(columns.stream().map(index -> index < row.cells().size() ? row.cells().get(index) : "").toList());
    }
    private void tableChunk(List<TextSegment> out, String text, Metadata base, int start, int end, List<Integer> columns, int part) {
        Metadata metadata = base.copy();
        metadata.put("source_row_start",Integer.toString(start)); metadata.put("source_row_end",Integer.toString(end));
        metadata.put("source_columns",columns.stream().map(c -> Integer.toString(c + 1)).collect(Collectors.joining(",")));
        metadata.put(SOURCE_LOCATION, (base.getString(SOURCE_LOCATION) == null ? "" : base.getString(SOURCE_LOCATION) + ";")
                + "rows=" + start + "-" + end + ";columns=" + metadata.getString("source_columns"));
        if (part > 0) metadata.put("source_row_fragment",Integer.toString(part));
        add(out,text,metadata);
    }

    /** 长单元格保留全部字符并记录同一行的分段；不在 UTF-16 代理对中间截断。 */
    private List<String> fragments(String prefix, String text) {
        List<String> result = new ArrayList<>(); int start = 0;
        while (start < text.length()) {
            int low = start + 1, high = text.length(), best = start;
            while (low <= high) {
                int end = (low + high) >>> 1;
                if (size(prefix + text.substring(start,end)) <= maxSize) { best = end; low = end + 1; }
                else high = end - 1;
            }
            if (best < text.length() && best > start && Character.isHighSurrogate(text.charAt(best - 1))) best--;
            if (best == start) fail();
            result.add(prefix + text.substring(start,best)); start = best;
            if (result.size() > maxChunks) fail();
        }
        return result;
    }
    private void add(List<TextSegment> out, String text, Metadata metadata) {
        if (out.size() >= maxChunks || size(text) > maxSize) fail();
        out.add(TextSegment.from(text,metadata));
    }
    private int size(String value) { return estimator == null ? value.length() : estimator.estimateTokenCountInText(value); }
    private static void fail() { throw new DocumentParsingException(DocumentParsingError.STRUCTURE_LIMIT,null); }
}
