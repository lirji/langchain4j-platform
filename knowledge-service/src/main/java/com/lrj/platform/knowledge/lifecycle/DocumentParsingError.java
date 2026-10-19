package com.lrj.platform.knowledge.lifecycle;

/** 文档解析错误目录：HTTP 提示和任务错误码共用，不以底层异常文案判断重试。 */
public enum DocumentParsingError {
    EMPTY("DOCUMENT_EMPTY", "document contains no extractable text", false),
    TEXT_LIMIT("DOCUMENT_TEXT_LIMIT", "extracted document text exceeds the configured limit", false),
    ENCRYPTED("DOCUMENT_ENCRYPTED", "encrypted document requires an unencrypted copy", false),
    INVALID("DOCUMENT_PARSE_FAILED", "document could not be parsed", false),
    INPUT_LIMIT("DOCUMENT_INPUT_LIMIT", "document input exceeds the configured byte limit", false),
    PAGE_LIMIT("DOCUMENT_PAGE_LIMIT", "document exceeds the configured page limit", false),
    RESOURCE_LIMIT("DOCUMENT_RESOURCE_LIMIT", "document parsing exceeded its resource budget", false),
    TIMEOUT("DOCUMENT_PARSE_TIMEOUT", "document parsing exceeded its execution deadline", false),
    PROCESS_FAILED("DOCUMENT_PARSER_PROCESS_FAILED", "isolated document parser failed", false),
    TERMINATION_FAILED("DOCUMENT_PARSER_TERMINATION_FAILED", "document parser termination requires operator attention", false),
    PROCESS_UNAVAILABLE("DOCUMENT_PARSER_UNAVAILABLE", "isolated document parser is unavailable; retry later", true),
    BUSY("DOCUMENT_PARSER_BUSY", "document parser capacity is busy; retry later", true),
    SOURCE_READ("DOCUMENT_SOURCE_READ_FAILED", "document source could not be read; retry later", true);

    private final String code;
    private final String message;
    private final boolean retryable;

    DocumentParsingError(String code, String message, boolean retryable) {
        this.code = code;
        this.message = message;
        this.retryable = retryable;
    }

    /** 稳定机器码写入已有任务 errorCode 字段。 */
    public String code() { return code; }

    /** 对外仅使用安全提示，不暴露文件名、URL、内部路径或底层异常。 */
    public String message() { return message; }

    /** 输入或不可恢复的进程故障需人工处理，暂时读取/启动失败与容量不足进入有界重试。 */
    public boolean retryable() { return retryable; }

    /** 执行器损坏需人工处理，但仍属于服务故障；不能因禁止自动重试就向客户端归类为坏输入。 */
    public boolean serviceFailure() {
        return retryable || this == PROCESS_FAILED || this == TERMINATION_FAILED;
    }
}
