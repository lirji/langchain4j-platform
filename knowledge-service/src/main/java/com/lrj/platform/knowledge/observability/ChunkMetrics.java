package com.lrj.platform.knowledge.observability;

import dev.langchain4j.data.segment.TextSegment;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 切分质量指标打点（Micrometer，迁移单体 {@code observability/ChunkMetrics}）。每次入库切分完成后记录
 * chunk 尺寸分布与碎块/超大块比例，让「换 chunking 策略 / 调 max-size 后切分质量怎么变」可观测。
 *
 * <p>打点（均带 {@code strategy} tag，便于按策略对比 recursive/markdown-header/parent-child/semantic）：
 * <ul>
 *   <li>{@code rag.chunk.size}（DistributionSummary，chars）— 每 chunk 字符长度 → Prometheus 给 {@code _count/_sum/_max}</li>
 *   <li>{@code rag.chunk.total}（counter）— 入库 chunk 总数</li>
 *   <li>{@code rag.chunk.tiny}（counter）— 小于 {@code tinyChars}（默 50）的碎块数（切太碎信号）</li>
 *   <li>{@code rag.chunk.oversize}（counter）— 大于 {@code oversizeChars}（默 2000）的超大块数（块太大信号）</li>
 *   <li>{@code rag.ingest.documents}（counter）— 入库文档数</li>
 * </ul>
 *
 * <p>尺寸统一按<strong>字符数</strong>计量（确定性、零 tokenizer 依赖）。始终在线（观测能力不藏开关后），打点成本可忽略。
 */
@Component
public class ChunkMetrics {

    private final MeterRegistry registry;
    private final int tinyChars;
    private final int oversizeChars;

    public ChunkMetrics(MeterRegistry registry,
                        @Value("${app.rag.metrics.tiny-chars:50}") int tinyChars,
                        @Value("${app.rag.metrics.oversize-chars:2000}") int oversizeChars) {
        this.registry = registry;
        this.tinyChars = tinyChars;
        this.oversizeChars = oversizeChars;
    }

    /**
     * 记录一次入库的切分质量。
     *
     * @param strategy      切分策略（{@code DocumentSplitterFactory.strategy()}），作 tag
     * @param documentCount 本次入库的文档数
     * @param segments      切分后的最终 chunk（已含 Contextual 前缀等改写，反映真实入库形态）
     */
    public void record(String strategy, int documentCount, List<TextSegment> segments) {
        Tags tags = Tags.of("strategy", strategy == null || strategy.isBlank() ? "unknown" : strategy);
        registry.counter("rag.ingest.documents", tags).increment(documentCount);
        if (segments == null || segments.isEmpty()) {
            return;
        }
        int tiny = 0;
        int oversize = 0;
        for (TextSegment seg : segments) {
            int len = seg.text() == null ? 0 : seg.text().length();
            registry.summary("rag.chunk.size", tags).record(len);
            if (len < tinyChars) {
                tiny++;
            }
            if (len > oversizeChars) {
                oversize++;
            }
        }
        registry.counter("rag.chunk.total", tags).increment(segments.size());
        if (tiny > 0) {
            registry.counter("rag.chunk.tiny", tags).increment(tiny);
        }
        if (oversize > 0) {
            registry.counter("rag.chunk.oversize", tags).increment(oversize);
        }
    }
}
