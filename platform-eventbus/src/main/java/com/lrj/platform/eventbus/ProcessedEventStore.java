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
     * <p>返回值本身是原子的「首次占位」判定，因此也可当**声明占用（claim）**用于并发单飞场景：
     * 见 {@link InboundIdempotency}——入站 webhook 的重投可能并发到达多个副本，
     * 「先查后标记」两步之间存在窗口，两副本会同时通过检查；此时必须直接用本方法的返回值抢占，
     * 并在处理失败时 {@link #releaseClaim(String)} 归还，否则失败的消息会被永久当成已处理。
     *
     * @param eventId 事件唯一标识
     * @return true = 首次记录；false = 已存在
     */
    boolean markProcessed(String eventId);

    /**
     * 撤销一次 {@link #markProcessed(String)}，让上游的重投能再次进入处理。
     *
     * <p><b>只允许在「标记即抢占」语义下、且处理确实失败时调用</b>：如果消费者用的是
     * 「先查 → 处理 → 成功后标记」（Kafka listener 的顺序），失败时压根没标记过，不需要撤销。
     * 撤销一个已成功处理的 eventId 会让重投产生重复副作用。
     *
     * @param eventId 事件唯一标识
     */
    void releaseClaim(String eventId);
}
