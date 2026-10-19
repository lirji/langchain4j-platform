package com.lrj.platform.knowledge.lifecycle;

import java.util.Objects;

/** 保留输入异常基类兼容性；重试策略优先使用明确错误类别，原始原因只在服务端记录。 */
public final class DocumentParsingException extends IllegalArgumentException {
    private final DocumentParsingError error;

    /** 将安全提示与内部原因分离，避免解析库异常进入响应头。 */
    public DocumentParsingException(DocumentParsingError error, Throwable cause) {
        super(Objects.requireNonNull(error).message(), cause);
        this.error = error;
    }

    /** 返回稳定分类供 HTTP 和 worker 使用。 */
    public DocumentParsingError error() { return error; }
}
