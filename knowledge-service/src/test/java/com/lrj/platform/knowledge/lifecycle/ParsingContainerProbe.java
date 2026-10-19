package com.lrj.platform.knowledge.lifecycle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 仅供隔离容器 smoke：挂载 test-classes，真实 fat jar 父进程再 fork 解析；不启动业务服务。 */
public final class ParsingContainerProbe {
    private ParsingContainerProbe() { }

    /** 固定无敏感信息的语料与预期，CLI 只输出测试结果和耗时。 */
    public static void main(String[] args) throws Exception {
        var cases = Map.of("pdf-two-pages-manual.pdf", "PAGE_2",
                "docx-headings-table-guide.docx", "SKU_001",
                "xlsx-long-rows-rows.xlsx", "ITEM_0100",
                "csv-long-rows-rows.csv", "ITEM_0100");
        var properties = new DocumentParsingProperties();
        properties.setTimeout(java.time.Duration.ofSeconds(30));
        try (var extractor = new DocumentTextExtractor(properties)) {
            for (var sample : cases.entrySet()) {
                long start = System.nanoTime();
                try (var input = Files.newInputStream(Path.of(args[0]).resolve(sample.getKey()))) {
                    if (!extractor.extract(input, sample.getKey()).contains(sample.getValue())) {
                        throw new AssertionError("required fixture marker is missing");
                    }
                }
                System.out.println("PASS " + sample.getKey() + " elapsedMs=" + (System.nanoTime() - start) / 1_000_000);
            }
        }
        try (var paths = Files.list(properties.getTempRoot())) {
            if (paths.findAny().isPresent()) throw new AssertionError("temporary parser files leaked");
        }
        // 仅在 Linux cgroup v2 可用时记录整个容器的峰值，不能解释为单份文档的峰值 RSS。
        for (String name : new String[]{"memory.peak", "memory.max", "pids.max", "cpu.max"}) {
            Path metric = Path.of("/sys/fs/cgroup", name);
            if (Files.isReadable(metric)) System.out.println(name + "=" + Files.readString(metric).trim());
        }
    }
}
