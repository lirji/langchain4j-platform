package com.lrj.platform.knowledge.lifecycle;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.nio.file.Path;

/** 限制正文收集内存；该预算不替代解析进程、页数、附件或 OCR 资源隔离。 */
@Component
@ConfigurationProperties(prefix = "app.rag.parsing")
public class DocumentParsingProperties {
    public static final int DEFAULT_MAX_TEXT_CHARS = 2_000_000;
    private static final String INVALID_LIMIT = "app.rag.parsing.max-text-chars must be positive";
    private int maxTextChars = DEFAULT_MAX_TEXT_CHARS;
    private final DocumentOcrProperties ocr = new DocumentOcrProperties();

    /** OCR 单独 opt-in，默认原生解析行为保持不变。 */
    public DocumentOcrProperties getOcr() { return ocr; }
    public static final String IN_PROCESS = "in-process";
    public static final String FORKED = "forked";
    private String executionMode = FORKED;
    private long maxInputBytes = 50L * 1024 * 1024;
    private int maxResultBytes = 16 * 1024 * 1024;
    private long maxTemporaryBytes = 128L * 1024 * 1024;
    private int maxTemporaryFiles = 1000;
    private int maxPages = 500;
    private int maxStructureUnits = 100_000;
    private int maxProcesses = 2;
    private int heapMiB = 256;
    private Duration timeout = Duration.ofSeconds(120);
    private Duration inputTimeout = Duration.ofSeconds(60);
    private Duration terminationTimeout = Duration.ofSeconds(5);
    private Path tempRoot = Path.of(System.getProperty("java.io.tmpdir"), "knowledge-parser");

    /** 字符预算包含解析器输出的空白，超限整个任务失败而非静默截断入库。 */
    public int getMaxTextChars() { return maxTextChars; }

    /** 启动绑定时拒绝无效值，避免负数被解析库解释成无限制。 */
    public void setMaxTextChars(int maxTextChars) {
        if (maxTextChars <= 0) throw new IllegalArgumentException(INVALID_LIMIT);
        this.maxTextChars = maxTextChars;
    }

    /** 应用 YAML 缺省 forked，无参离线测试显式沿用 in-process 基线。 */
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String value) {
        if (!IN_PROCESS.equals(value) && !FORKED.equals(value)) throw new IllegalArgumentException("unknown parsing execution-mode");
        executionMode = value;
    }
    /** 输入字节和结果字节分别有界，避免字符预算被误认为原文预算。 */
    public long getMaxInputBytes() { return maxInputBytes; }
    public void setMaxInputBytes(long value) { positive(value); maxInputBytes = value; }
    public int getMaxResultBytes() { return maxResultBytes; }
    public void setMaxResultBytes(int value) {
        positive(value);
        if (value == Integer.MAX_VALUE) throw new IllegalArgumentException("parsing result limit must leave overflow detection space");
        maxResultBytes = value;
    }
    /** 目录监测预算不代替容器 tmpfs 的硬上限。 */
    public long getMaxTemporaryBytes() { return maxTemporaryBytes; }
    public void setMaxTemporaryBytes(long value) { positive(value); maxTemporaryBytes = value; }
    public int getMaxTemporaryFiles() { return maxTemporaryFiles; }
    public void setMaxTemporaryFiles(int value) { positive(value); maxTemporaryFiles = value; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int value) { positive(value); maxPages = value; }
    /** 块、行和单元格共同计数，避免空白表格耗尽结构对象内存。 */
    public int getMaxStructureUnits() { return maxStructureUnits; }
    public void setMaxStructureUnits(int value) { positive(value); maxStructureUnits = value; }
    /** 容量占满时拒绝，不把文档隐式堆积在无界等待队列。 */
    public int getMaxProcesses() { return maxProcesses; }
    public void setMaxProcesses(int value) { positive(value); maxProcesses = value; }
    public int getHeapMiB() { return heapMiB; }
    public void setHeapMiB(int value) { positive(value); heapMiB = value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { positive(value.toMillis()); timeout = value; }
    /** 暂存预算在每次读取前后检查；单次阻塞读取仍依赖原文存储的 socket timeout。 */
    public Duration getInputTimeout() { return inputTimeout; }
    public void setInputTimeout(Duration value) { positive(value.toMillis()); inputTimeout = value; }
    public Duration getTerminationTimeout() { return terminationTimeout; }
    public void setTerminationTimeout(Duration value) { positive(value.toMillis()); terminationTimeout = value; }
    public Path getTempRoot() { return tempRoot; }
    public void setTempRoot(Path value) { tempRoot = java.util.Objects.requireNonNull(value).toAbsolutePath().normalize(); }
    private static void positive(long value) {
        if (value <= 0) throw new IllegalArgumentException("parsing resource limits must be positive");
    }
}
