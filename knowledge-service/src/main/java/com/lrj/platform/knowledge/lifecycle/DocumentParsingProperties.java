package com.lrj.platform.knowledge.lifecycle;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 限制正文收集内存；该预算不替代解析进程、页数、附件或 OCR 资源隔离。 */
@Component
@ConfigurationProperties(prefix = "app.rag.parsing")
public class DocumentParsingProperties {
    public static final int DEFAULT_MAX_TEXT_CHARS = 2_000_000;
    private static final String INVALID_LIMIT = "app.rag.parsing.max-text-chars must be positive";
    private int maxTextChars = DEFAULT_MAX_TEXT_CHARS;

    /** 字符预算包含解析器输出的空白，超限整个任务失败而非静默截断入库。 */
    public int getMaxTextChars() { return maxTextChars; }

    /** 启动绑定时拒绝无效值，避免负数被解析库解释成无限制。 */
    public void setMaxTextChars(int maxTextChars) {
        if (maxTextChars <= 0) throw new IllegalArgumentException(INVALID_LIMIT);
        this.maxTextChars = maxTextChars;
    }
}
