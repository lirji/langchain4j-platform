package com.lrj.platform.knowledge.lifecycle;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

/** 来源字段白名单：结构定位可跨检索源流转，租户/权限/任意模型配置不能经扩展 metadata 注入。 */
public final class SourceProvenance {
    private static final Set<String> KEYS = Set.of("source_format","source_block","source_kind","source_location",
            "chunking_actual_strategy","contextual_status",
            "source_extraction_method","source_native_chars","source_ocr_chars","source_ocr_confidence",
            "source_page","source_rotation","source_sheet","source_paragraph","source_table","source_row_start","source_row_end",
            "source_line_start","source_line_end","source_columns","source_row_fragment","source_code_fragment","source_pages_without_text",
            "parsing_warnings","section","breadcrumb");
    private SourceProvenance() { }
    /** 仅存储来源与原文，ES 禁止对这张对象做动态字段展开。 */
    public static Map<String,String> stored(Metadata metadata) {
        var values = new LinkedHashMap<String,String>(publicMetadata(metadata.toMap()));
        String raw = metadata.getString(ParsedDocument.SOURCE_TEXT);
        if (raw != null) values.put(ParsedDocument.SOURCE_TEXT,raw);
        return Map.copyOf(values);
    }
    /** 对外只返回可回查位置及告警，不把内部身份或重复全文放进扩展字段。 */
    public static Map<String,String> publicMetadata(Map<String,?> values) {
        var result = new LinkedHashMap<String,String>();
        if (values != null) values.forEach((key,value) -> { if (KEYS.contains(key) && value instanceof String text) result.put(key,text); });
        return Map.copyOf(result);
    }
    /** 模型生成的前缀只用于检索，不充当引用证据。 */
    public static String sourceText(TextSegment segment) {
        String raw = segment.metadata().getString(ParsedDocument.SOURCE_TEXT); return raw == null ? segment.text() : raw;
    }
}
