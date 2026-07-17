package com.lrj.platform.knowledge.observability;

import dev.langchain4j.data.segment.TextSegment;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChunkMetricsTest：验证 {@link ChunkMetrics} 向 Micrometer 注册表按 {@code strategy} 标签记录切块指标——
 * 文档数、chunk 总数、碎块与超大块计数及尺寸分布摘要；空段时只记文档数、空白策略归为 {@code unknown}、
 * 无碎块/超大块时对应 counter 不创建。
 */
class ChunkMetricsTest {

    private static TextSegment seg(int len) {
        return TextSegment.from("x".repeat(len));
    }

    @Test
    void records_size_total_tiny_oversize_withStrategyTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChunkMetrics metrics = new ChunkMetrics(registry, 50, 2000);

        // 3 个 chunk：碎块(10) / 正常(500) / 超大(2500)
        metrics.record("recursive", 1, List.of(seg(10), seg(500), seg(2500)));

        Tags tags = Tags.of("strategy", "recursive");
        assertThat(registry.get("rag.ingest.documents").tags(tags).counter().count()).isEqualTo(1.0);
        assertThat(registry.get("rag.chunk.total").tags(tags).counter().count()).isEqualTo(3.0);
        assertThat(registry.get("rag.chunk.tiny").tags(tags).counter().count()).isEqualTo(1.0);
        assertThat(registry.get("rag.chunk.oversize").tags(tags).counter().count()).isEqualTo(1.0);
        var summary = registry.get("rag.chunk.size").tags(tags).summary();
        assertThat(summary.count()).isEqualTo(3);
        assertThat(summary.totalAmount()).isEqualTo(10 + 500 + 2500);
        assertThat(summary.max()).isEqualTo(2500);
    }

    @Test
    void emptySegments_onlyDocumentsCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChunkMetrics metrics = new ChunkMetrics(registry, 50, 2000);

        metrics.record("semantic", 2, List.of());

        assertThat(registry.get("rag.ingest.documents").tags("strategy", "semantic").counter().count()).isEqualTo(2.0);
        // 无 chunk → 不建 total/size 计
        assertThat(registry.find("rag.chunk.total").counter()).isNull();
    }

    @Test
    void blankStrategy_taggedUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ChunkMetrics(registry, 50, 2000).record("  ", 1, List.of(seg(100)));
        assertThat(registry.get("rag.chunk.total").tags("strategy", "unknown").counter().count()).isEqualTo(1.0);
    }

    @Test
    void noTinyOrOversize_countersAbsent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ChunkMetrics(registry, 50, 2000).record("recursive", 1, List.of(seg(200), seg(300)));
        assertThat(registry.find("rag.chunk.tiny").counter()).isNull();
        assertThat(registry.find("rag.chunk.oversize").counter()).isNull();
    }
}
