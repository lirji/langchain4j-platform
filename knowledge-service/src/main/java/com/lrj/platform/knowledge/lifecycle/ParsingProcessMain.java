package com.lrj.platform.knowledge.lifecycle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 解析专用子进程入口，只使用临时目录和预算参数，不启动 Spring 或加载业务凭据。 */
public final class ParsingProcessMain {
    static final String INPUT = "input";
    static final String OUTPUT = "output.txt";
    static final String ERROR = "error-code";
    private ParsingProcessMain() { }

    /** 错误通道只输出稳定 code；详细正文和 JVM 输出不通过父进程响应暴露。 */
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        // 父 JVM 被 SIGKILL 时没有 shutdown hook；子进程自行结束，避免在同一宿主继续消耗资源。
        long parentId = Long.parseLong(args[3]);
        Thread.ofPlatform().daemon().name("parser-parent-watchdog").start(() -> {
            while (ProcessHandle.of(parentId).map(ProcessHandle::isAlive).orElse(false)) {
                try { Thread.sleep(250); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
            Runtime.getRuntime().halt(70);
        });
        var properties = new DocumentParsingProperties();
        properties.setExecutionMode(DocumentParsingProperties.IN_PROCESS);
        properties.setMaxTextChars(Integer.parseInt(args[1]));
        properties.setMaxPages(Integer.parseInt(args[2]));
        properties.setMaxStructureUnits(Integer.parseInt(args[6]));
        try (var input = Files.newInputStream(directory.resolve(INPUT))) {
            if (Boolean.parseBoolean(args[4])) {
                var parsed = new StructuredDocumentParser(properties).parse(directory.resolve(INPUT), args[5]);
                ParsedDocumentCodec.write(directory.resolve(OUTPUT), parsed);
            } else {
                String text = new DocumentTextExtractor(properties).extract(input, "isolated-document");
                Files.writeString(directory.resolve(OUTPUT), text, StandardCharsets.UTF_8);
            }
        } catch (DocumentParsingException failure) {
            Files.writeString(directory.resolve(ERROR), failure.error().code(), StandardCharsets.US_ASCII);
        }
    }
}
