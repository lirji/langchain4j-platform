package com.lrj.platform.eventbus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;

/**
 * 入站回调的幂等接入点：把「同一条外部消息只处理一次」的正确顺序收在一处，供各渠道回调复用。
 *
 * <p><b>为什么入站回调不能照抄 Kafka listener 的顺序。</b>Kafka 消费者用「先查 → 处理 → 成功后标记」，
 * 失败时不标记、靠重投重来。入站 webhook 不同：控制器必须在几秒内 ack（钉钉/飞书都是 3s），
 * 真正处理是异步的，而渠道的重投可能并发打到多个副本——「先查后标记」两步之间的窗口会让两个副本
 * 同时通过检查、各回一次，重复副作用（重复回复、重复起流程、重复 LLM 花费）照样发生。
 * 因此这里改成**抢占语义**：用 {@link ProcessedEventStore#markProcessed} 的原子返回值抢占，抢到才处理。
 *
 * <p><b>抢占必须可归还。</b>抢占后若处理失败（或线程池拒收），必须
 * {@link ProcessedEventStore#releaseClaim} 归还，否则这条消息被永久当成「已处理」，渠道重投也进不来，
 * 等于静默丢消息——这正是各 bridge 早期用进程内 map「先 put 再处理」时的缺陷。
 *
 * <p>{@code source} 前缀让不同渠道的消息 id 落进同一张 {@code PROCESSED_EVENT} 表也不会互撞
 * （钉钉 msgId 与飞书 messageId 由各自平台生成，不保证全局唯一）。
 */
public final class InboundIdempotency {

    private static final Logger log = LoggerFactory.getLogger(InboundIdempotency.class);

    private final ProcessedEventStore store;
    private final String source;

    /**
     * @param store  去重存储（内存或 JDBC，由 {@code platform.eventbus.processed-event-store} 选择；
     *               多副本部署必须用 JDBC，内存实现每个副本各去重一份）
     * @param source 渠道标识，如 {@code dingtalk} / {@code feishu}，用于 key 命名空间
     */
    public InboundIdempotency(ProcessedEventStore store, String source) {
        this.store = store;
        this.source = source;
    }

    /** {@code inbound:<source>:<messageId>}。 */
    public static String key(String source, String messageId) {
        return "inbound:" + source + ":" + messageId;
    }

    /**
     * 抢占一条入站消息的处理权。
     *
     * @param messageId 渠道消息 id；为 null/空表示渠道没给可去重的 id，此时只能放行（无法去重）
     * @return true = 抢到，调用方应处理并在失败时 {@link #release(String)}；false = 已被处理或正在处理，应跳过
     */
    public boolean claim(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            // 没有稳定 id 就无法幂等。宁可处理（可能重复）也不丢：入站消息丢失对用户可见，重复只是冗余回复。
            return true;
        }
        return store.markProcessed(key(source, messageId));
    }

    /** 归还抢占，让渠道重投能再次进入处理。仅在处理失败时调用。 */
    public void release(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        store.releaseClaim(key(source, messageId));
    }

    /**
     * 抢占 → 异步执行 → 失败归还的完整范式。抢占在调用线程同步完成（控制器 ack 之前就完成去重判定，
     * 重复消息不会先排进队列再被丢），执行交给渠道自己的线程池。
     *
     * @return true = 已受理（抢到并提交执行）；false = 重复消息，已跳过
     */
    public boolean submitOnce(String messageId, Executor executor, Runnable action) {
        if (!claim(messageId)) {
            log.debug("inbound message deduplicated source={} messageId={}", source, messageId);
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    // 处理失败 → 归还抢占，让渠道重投有机会重来（渠道未收到业务结果时通常会重投）。
                    // 这里不再往外抛：调用方是 fire-and-forget 的线程池，抛出去只会变成 uncaught 噪音，
                    // 而失败信息（含栈）在这条日志里已完整保留。
                    release(messageId);
                    log.warn("inbound message processing failed, claim released source={} messageId={}",
                            source, messageId, e);
                } catch (Error e) {
                    // Error（OOM/StackOverflow 等）不吞：归还抢占后原样抛出，交给线程池的未捕获处理器
                    release(messageId);
                    throw e;
                }
            });
            return true;
        } catch (RuntimeException e) {
            // 线程池拒收（队列满 / 已关闭）同样是"没处理成功"，必须归还，否则这条消息永久消失
            release(messageId);
            throw e;
        }
    }
}
