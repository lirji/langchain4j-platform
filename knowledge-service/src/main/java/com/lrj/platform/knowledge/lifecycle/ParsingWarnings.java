package com.lrj.platform.knowledge.lifecycle;

/** 解析质量告警协议：只使用稳定 code 与结构位置，不携带异常堆栈或文件内容。 */
public final class ParsingWarnings {
    public static final String HEADER_ASSUMED = "FIRST_NONEMPTY_ROW_USED_AS_HEADER";
    public static final String FORMULA_CACHE = "FORMULA_CACHED_VALUE_NOT_RECALCULATED";
    public static final String MERGED_CELLS = "MERGED_CELLS_NOT_EXPANDED";
    public static final String HIDDEN_CONTENT = "HIDDEN_SHEET_OR_ROW_INCLUDED";
    public static final String PDF_OCR = "PDF_OCR_APPLIED";
    public static final String PDF_OCR_MERGED = "PDF_NATIVE_OCR_MERGED_READING_ORDER_UNVERIFIED";
    public static final String PDF_BLANK = "PDF_BLANK_PAGE";
    public static final String PDF_NO_TEXT = "PDF_PAGE_WITHOUT_TEXT";
    public static final String PDF_READING_ORDER = "PDF_READING_ORDER_UNVERIFIED";
    public static final String PDF_ROTATED = "PDF_ROTATED_PAGE";
    public static final String EMBEDDED_SKIPPED = "EMBEDDED_ATTACHMENTS_NOT_PARSED";
    public static final String WORD_AUXILIARY = "WORD_AUXILIARY_CONTENT_NOT_INDEXED";
    public static final String WORD_REVISIONS = "WORD_TRACKED_CHANGES_NOT_RESOLVED";
    public static final String WORD_LIST_LABELS = "WORD_LIST_LABELS_NOT_RENDERED";
    public static final String STRUCTURE_UNAVAILABLE = "FORMAT_STRUCTURE_NOT_AVAILABLE";
    private ParsingWarnings() { }
}
