package com.lrj.platform.knowledge.ingest.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 每个来源每轮仅处理一页，失败来源不会阻断其他来源，避免无界追赶占用资源。 */
public final class KnowledgeSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeSyncScheduler.class);
    private final KnowledgeSyncProperties properties;
    private final KnowledgeSyncService service;
    public KnowledgeSyncScheduler(KnowledgeSyncProperties properties, KnowledgeSyncService service) {
        this.properties = properties; this.service = service;
    }
    /** 固定延迟避免同一实例重叠轮询；跨实例执行权由数据库租约控制。 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void poll() {
        for (var source : properties.sources()) {
            try { service.poll(source); }
            catch (RuntimeException failure) {
                log.warn("knowledge HTTP sync unavailable source={} code={}", source.id(),
                        failure instanceof SyncFailure known ? known.code() : "SYNC_UNAVAILABLE");
            }
        }
    }
}
