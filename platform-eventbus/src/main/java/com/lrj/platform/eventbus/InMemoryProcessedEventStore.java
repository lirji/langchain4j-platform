package com.lrj.platform.eventbus;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存去重（默认）。进程内有效，重启后失忆——跨重启强去重请用 {@link JdbcProcessedEventStore}。
 */
public class InMemoryProcessedEventStore implements ProcessedEventStore {

    private final ConcurrentHashMap<String, Boolean> seen = new ConcurrentHashMap<>();

    @Override
    public boolean markProcessed(String eventId) {
        // putIfAbsent 返回 null 表示此前不存在（首次）
        return seen.putIfAbsent(eventId, Boolean.TRUE) == null;
    }
}
