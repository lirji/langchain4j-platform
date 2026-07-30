package com.lrj.platform.knowledge.ingest.job;

import org.springframework.scheduling.annotation.Scheduled;

/** 仅在显式启用 GC 时装配；重复扫描和精确版本删除均为幂等。 */
public class KnowledgeVersionGcLoop {

    private final KnowledgeVersionGarbageCollector collector;

    public KnowledgeVersionGcLoop(KnowledgeVersionGarbageCollector collector) {
        this.collector = collector;
    }

    @Scheduled(
            fixedDelayString = "${app.rag.ingestion.version-gc.poll-interval:PT1H}",
            initialDelayString = "${app.rag.ingestion.version-gc.poll-interval:PT1H}")
    public void collect() {
        collector.runOnce();
    }
}
