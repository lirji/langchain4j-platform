package com.lrj.platform.knowledge.lifecycle;

/** 文档解析错误目录：HTTP 提示和任务错误码共用，不以底层异常文案判断重试。 */
public enum DocumentParsingError {
    EMPTY("DOCUMENT_EMPTY", "document contains no extractable text", false),
    TEXT_LIMIT("DOCUMENT_TEXT_LIMIT", "extracted document text exceeds the configured limit", false),
    ENCRYPTED("DOCUMENT_ENCRYPTED", "encrypted document requires an unencrypted copy", false),
    INVALID("DOCUMENT_PARSE_FAILED", "document could not be parsed", false),
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

    /** 输入本身有问题需用户修正，只有原文读取故障进入有界重试。 */
    public boolean retryable() { return retryable; }
}
