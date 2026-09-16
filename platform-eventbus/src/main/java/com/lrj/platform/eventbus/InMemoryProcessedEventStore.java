package com.lrj.platform.eventbus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内存去重（默认）。进程内有效，重启后失忆——跨重启强去重请用 {@link JdbcProcessedEventStore}。
 *
 * <p>去重窗口**有界**：最多保留 {@code maxEntries} 个 eventId，超出按最早插入淘汰。入站回调
 * （见 {@link InboundIdempotency}）在长期运行的进程里会持续产生新 id，无界 map 等于内存泄漏；
 * 有界窗口把内存换成「极旧事件重投可能被重复处理」的风险，这在至少一次投递下本就要靠业务幂等兜底，
 * 而渠道/消息中间件的重投窗口远小于默认 10 万条。要求严格跨重启、无窗口限制时用 JDBC 实现。
 */
public class InMemoryProcessedEventStore implements ProcessedEventStore {

    /** 默认窗口大小：够覆盖任何现实重投窗口，同时把内存占用限制在数 MB 量级。 */
    public static final int DEFAULT_MAX_ENTRIES = 100_000;

    private final Map<String, Boolean> seen;

    public InMemoryProcessedEventStore() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public InMemoryProcessedEventStore(int maxEntries) {
        int capacity = Math.max(1, maxEntries);
        // LinkedHashMap 的 removeEldestEntry 是 JDK 自带的有界窗口做法；访问顺序保持插入序（FIFO），
        // 因为去重判定不该让"最近被查过"的旧事件无限续命。
        this.seen = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    @Override
    public boolean isProcessed(String eventId) {
        synchronized (seen) {
            return seen.containsKey(eventId);
        }
    }

    @Override
    public boolean markProcessed(String eventId) {
        synchronized (seen) {
            // putIfAbsent 返回 null 表示此前不存在（首次）
            return seen.putIfAbsent(eventId, Boolean.TRUE) == null;
        }
    }

    @Override
    public void releaseClaim(String eventId) {
        synchronized (seen) {
            seen.remove(eventId);
        }
    }
}
