package com.lrj.platform.channel.inbox;

import com.lrj.platform.channel.inbox.mapper.InboundInboxStoreMapper;
import com.lrj.platform.channel.inbox.mapper.InboundInboxStoreRow;
import com.lrj.platform.persistence.PlusMapperFactory;
import com.lrj.platform.persistence.Rows;

import org.springframework.dao.DuplicateKeyException;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/** JDBC 是 ACK 后消息的权威存储；所有写入都带状态、epoch 和租户条件。 */
public final class JdbcInboundInboxStore implements InboundInboxStore {

    private final InboundInboxStoreMapper db;

    public JdbcInboundInboxStore(DataSource dataSource) {
        this.db = PlusMapperFactory.create(dataSource, InboundInboxStoreMapper.class);
        // 应用只验证迁移结果，不以启动 DDL 掩盖发布缺失。
        db.readContract1();
    }

    @Override
    public boolean receive(InboundInboxMessage m) {
        try {
            db.writeReceive1(
                    m.inboxId(),
                    m.tenantId(),
                    m.source(),
                    m.messageId(),
                    m.payloadHash(),
                    m.payload(),
                    m.traceId(),
                    m.createdAt(),
                    m.createdAt(),
                    m.createdAt());
            return true;
        } catch (DuplicateKeyException duplicate) {
            List<String> hashes = Rows.map(db.readReceive1(m.inboxId(), m.tenantId(), m.source()), (rs, row) -> rs.payloadHash);
            if (hashes.size() != 1 || !m.payloadHash().equals(hashes.getFirst())) {
                throw new PayloadConflictException();
            }
            return false;
        }
    }

    @Override
    public List<InboundInboxMessage> claimDue(String tenant, String source, String owner,
                                             long now, long leaseMillis, int limit) {
        if (leaseMillis <= 0 || owner == null || owner.isBlank()) throw new IllegalArgumentException("invalid lease");
        int bounded = Math.clamp(limit, 1, 100);
        List<InboundInboxMessage> candidates = Rows.map(db.readClaimDue1(tenant, source, now, now, bounded), this::map);
        List<InboundInboxMessage> claimed = new ArrayList<>();
        for (InboundInboxMessage m : candidates) {
            // 先读候选、再条件更新；多副本竞抢靠数据库 CAS，避免依赖特定数据库的 SKIP LOCKED 语法。
            int changed = db.writeClaimDue1(owner, Math.addExact(now, leaseMillis), now, m.inboxId(), tenant, m.leaseEpoch(), now, now);
            if (changed == 1) {
                // 返回本次 CAS 的快照，不在第二次 SELECT 中误拿已经被另一执行器接管的租约。
                claimed.add(new InboundInboxMessage(m.inboxId(), tenant, source, m.messageId(),
                        m.payloadHash(), m.payload(), m.traceId(), InboundInboxMessage.RUNNING,
                        m.attempts() + 1, owner, m.leaseEpoch() + 1, now + leaseMillis,
                        m.nextAttemptAt(), m.createdAt(), now, null));
            }
        }
        return List.copyOf(claimed);
    }

    @Override
    public boolean succeed(InboundInboxMessage claim, long now) {
        return finish(claim, now, now, InboundInboxMessage.SUCCEEDED, null);
    }

    @Override
    public boolean renew(InboundInboxMessage c, long now, long until) {
        return db.writeRenew1(until, now, c.inboxId(), c.tenantId(), c.leaseOwner(), c.leaseEpoch(), now) == 1;
    }

    @Override
    public boolean retry(InboundInboxMessage claim, long now, long next, boolean dead, String errorCode) {
        return finish(claim, now, next, dead ? InboundInboxMessage.DEAD : InboundInboxMessage.PENDING, errorCode);
    }

    private boolean finish(InboundInboxMessage c, long now, long next, String status, String error) {
        return db.writeFinish1(status, next, now, error, c.inboxId(), c.tenantId(), c.leaseOwner(), c.leaseEpoch(), now) == 1;
    }

    @Override
    public List<InboundInboxMessage> list(String tenantId, int limit) {
        return Rows.map(db.readList1(tenantId, Math.clamp(limit, 1, 100)), this::map);
    }

    @Override
    public boolean replay(String tenant, String id, long now) {
        return db.writeReplay1(now, now, id, tenant) == 1;
    }

    @Override
    public int cleanupSucceeded(long before, int limit) {
        List<String> ids = Rows.map(db.readCleanupSucceeded1(before, Math.clamp(limit, 1, 100)), (rs, row) -> rs.inboxId);
        int removed = 0;
        for (String id : ids) {
            removed += db.writeCleanupSucceeded1(id, before);
        }
        return removed;
    }

    private InboundInboxMessage map(InboundInboxStoreRow r, int row) {
        return new InboundInboxMessage(r.inboxId, r.tenantId,
                r.sourceName, r.messageId, r.payloadHash,
                r.payloadJson, r.traceId, r.statusCode,
                Rows.intValue(r.attempts), r.leaseOwner, Rows.longValue(r.leaseEpoch),
                Rows.longValue(r.leaseUntil), Rows.longValue(r.nextAttemptAt), Rows.longValue(r.createdAt),
                Rows.longValue(r.updatedAt), r.errorCode);
    }
}
