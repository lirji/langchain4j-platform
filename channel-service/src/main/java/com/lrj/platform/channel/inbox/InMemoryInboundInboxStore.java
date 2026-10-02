package com.lrj.platform.channel.inbox;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单进程开发存储。容量满时拒收而非淘汰未决消息；生产恢复必须选择 JDBC。 */
public final class InMemoryInboundInboxStore implements InboundInboxStore {
    private final Map<String, InboundInboxMessage> messages = new LinkedHashMap<>();
    private final int capacity;

    /** 显式容量避免将开发模式变成无界队列。 */
    public InMemoryInboundInboxStore(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    @Override
    public synchronized boolean receive(InboundInboxMessage m) {
        var old = messages.get(m.inboxId());
        if (old != null) {
            if (!old.payloadHash().equals(m.payloadHash())) throw new PayloadConflictException();
            return false;
        }
        if (messages.size() >= capacity) throw new IllegalStateException("inbox capacity reached");
        messages.put(m.inboxId(), m);
        return true;
    }

    @Override
    public synchronized List<InboundInboxMessage> claimDue(String tenant, String source, String owner,
                                                            long now, long leaseMillis, int limit) {
        if (leaseMillis <= 0 || owner == null || owner.isBlank()) throw new IllegalArgumentException("invalid lease");
        var claims = messages.values().stream().filter(m -> m.tenantId().equals(tenant) && m.source().equals(source))
                .filter(m -> (m.status().equals(InboundInboxMessage.PENDING) && m.nextAttemptAt() <= now)
                        || (m.status().equals(InboundInboxMessage.RUNNING) && m.leaseUntil() <= now))
                .sorted(Comparator.comparingLong(InboundInboxMessage::createdAt).thenComparing(InboundInboxMessage::inboxId))
                .limit(Math.clamp(limit, 1, 100))
                .map(m -> copy(m, InboundInboxMessage.RUNNING, m.attempts() + 1, owner,
                        m.leaseEpoch() + 1, Math.addExact(now, leaseMillis), m.nextAttemptAt(), now, null)).toList();
        claims.forEach(m -> messages.put(m.inboxId(), m));
        return claims;
    }

    @Override
    public synchronized boolean renew(InboundInboxMessage c, long now, long until) {
        var m = owned(c, now);
        if (m == null) return false;
        messages.put(m.inboxId(), copy(m, m.status(), m.attempts(), m.leaseOwner(), m.leaseEpoch(),
                until, m.nextAttemptAt(), now, null));
        return true;
    }

    @Override
    public synchronized boolean succeed(InboundInboxMessage c, long now) {
        return finish(c, now, now, InboundInboxMessage.SUCCEEDED, null);
    }

    @Override
    public synchronized boolean retry(InboundInboxMessage c, long now, long next, boolean dead, String error) {
        return finish(c, now, next, dead ? InboundInboxMessage.DEAD : InboundInboxMessage.PENDING, error);
    }

    private boolean finish(InboundInboxMessage c, long now, long next, String status, String error) {
        var m = owned(c, now);
        if (m == null) return false;
        messages.put(m.inboxId(), copy(m, status, m.attempts(), null, m.leaseEpoch(), 0, next, now, error));
        return true;
    }

    private InboundInboxMessage owned(InboundInboxMessage c, long now) {
        var m = messages.get(c.inboxId());
        return m != null && m.tenantId().equals(c.tenantId()) && m.status().equals(InboundInboxMessage.RUNNING)
                && m.leaseEpoch() == c.leaseEpoch() && java.util.Objects.equals(m.leaseOwner(), c.leaseOwner())
                && m.leaseUntil() > now ? m : null;
    }

    @Override
    public synchronized List<InboundInboxMessage> list(String tenant, int limit) {
        return messages.values().stream().filter(m -> m.tenantId().equals(tenant))
                .sorted(Comparator.comparingLong(InboundInboxMessage::createdAt).reversed()
                        .thenComparing(InboundInboxMessage::inboxId)).limit(Math.clamp(limit, 1, 100)).toList();
    }

    @Override
    public synchronized boolean replay(String tenant, String id, long now) {
        var m = messages.get(id);
        if (m == null || !m.tenantId().equals(tenant) || !m.status().equals(InboundInboxMessage.DEAD)) return false;
        messages.put(id, copy(m, InboundInboxMessage.PENDING, 0, null, m.leaseEpoch(), 0, now, now, null));
        return true;
    }

    @Override
    public synchronized int cleanupSucceeded(long before, int limit) {
        var ids = messages.values().stream().filter(m -> m.status().equals(InboundInboxMessage.SUCCEEDED)
                && m.updatedAt() < before).limit(Math.clamp(limit, 1, 100)).map(InboundInboxMessage::inboxId).toList();
        ids.forEach(messages::remove);
        return ids.size();
    }

    private static InboundInboxMessage copy(InboundInboxMessage m, String status, int attempts, String owner,
                                            long epoch, long until, long next, long updated, String error) {
        return new InboundInboxMessage(m.inboxId(), m.tenantId(), m.source(), m.messageId(), m.payloadHash(),
                m.payload(), m.traceId(), status, attempts, owner, epoch, until, next, m.createdAt(), updated, error);
    }
}
