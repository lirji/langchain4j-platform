package com.lrj.platform.eventbus;

/**
 * 消费幂等去重存储。至少一次投递下，消费者用它保证同一 eventId 只处理一次。
 * 默认 {@link InMemoryProcessedEventStore}（进程内）；开
 * {@code platform.eventbus.processed-event-store=jdbc} 切 {@link JdbcProcessedEventStore}（跨重启）。
 */
public interface ProcessedEventStore {

    /**
     * 只读判断 eventId 是否已处理完成。用于消费者「先查 → 处理 → 成功后标记」的正确顺序：
     * 处理成功前不标记，处理抛异常时消息重投会再次进入（不丢），已完成的事件在重投时被此检查跳过（去重）。
     *
     * @param eventId 事件唯一标识
     * @return true = 已处理完成（应跳过）；false = 未处理（应处理）
     */
    boolean isProcessed(String eventId);

    /**
     * 记录 eventId 已处理完成。<b>务必在处理成功之后调用</b>（作为提交点）。首次记录返回 true，
     * 重复记录返回 false（幂等，不报错）。
     *
     * @param eventId 事件唯一标识
     * @return true = 首次记录；false = 已存在
     */
    boolean markProcessed(String eventId);
}
