package com.lrj.platform.eventbus;

/**
 * 消费幂等去重存储。至少一次投递下，消费者用它保证同一 eventId 只处理一次。
 * 默认 {@link InMemoryProcessedEventStore}（进程内）；开
 * {@code platform.eventbus.processed-event-store=jdbc} 切 {@link JdbcProcessedEventStore}（跨重启）。
 */
public interface ProcessedEventStore {

    /**
     * 首次见到 eventId 时记录并返回 true；已见过返回 false（调用方据此跳过重复处理）。
     *
     * @param eventId 事件唯一标识
     * @return true = 首次（应处理）；false = 重复（应跳过）
     */
    boolean markProcessed(String eventId);
}
