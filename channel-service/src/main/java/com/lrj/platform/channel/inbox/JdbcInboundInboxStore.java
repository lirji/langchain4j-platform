package com.lrj.platform.channel.inbox;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** JDBC 是 ACK 后消息的权威存储；所有写入都带状态、epoch 和租户条件。 */
public final class JdbcInboundInboxStore implements InboundInboxStore {

    private final JdbcTemplate jdbc;

    public JdbcInboundInboxStore(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
        // 应用只验证迁移结果，不以启动 DDL 掩盖发布缺失。
        jdbc.queryForList("SELECT INBOX_ID, LEASE_EPOCH, PAYLOAD_JSON FROM CHANNEL_INBOUND_INBOX WHERE 1=0");
    }

    @Override
    public boolean receive(InboundInboxMessage m) {
        try {
            jdbc.update("""
                    INSERT INTO CHANNEL_INBOUND_INBOX
                    (INBOX_ID, TENANT_ID, SOURCE_NAME, MESSAGE_ID, PAYLOAD_HASH, PAYLOAD_JSON, TRACE_ID,
                     STATUS_CODE, ATTEMPTS, LEASE_EPOCH, LEASE_UNTIL, NEXT_ATTEMPT_AT, CREATED_AT, UPDATED_AT)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, 0, 0, ?, ?, ?)""",
                    m.inboxId(), m.tenantId(), m.source(), m.messageId(), m.payloadHash(), m.payload(),
                    m.traceId(), m.createdAt(), m.createdAt(), m.createdAt());
            return true;
        } catch (DuplicateKeyException duplicate) {
            List<String> hashes = jdbc.query("""
                    SELECT PAYLOAD_HASH FROM CHANNEL_INBOUND_INBOX
                    WHERE INBOX_ID=? AND TENANT_ID=? AND SOURCE_NAME=?""",
                    (rs, row) -> rs.getString(1), m.inboxId(), m.tenantId(), m.source());
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
        List<InboundInboxMessage> candidates = jdbc.query("""
                SELECT * FROM CHANNEL_INBOUND_INBOX WHERE TENANT_ID=? AND SOURCE_NAME=?
                 AND ((STATUS_CODE='PENDING' AND NEXT_ATTEMPT_AT<=?)
                      OR (STATUS_CODE='RUNNING' AND LEASE_UNTIL<=?))
                ORDER BY CREATED_AT, INBOX_ID LIMIT ?""", this::map, tenant, source, now, now, bounded);
        List<InboundInboxMessage> claimed = new ArrayList<>();
        for (InboundInboxMessage m : candidates) {
            // 先读候选、再条件更新；多副本竞抢靠数据库 CAS，避免依赖特定数据库的 SKIP LOCKED 语法。
            int changed = jdbc.update("""
                    UPDATE CHANNEL_INBOUND_INBOX SET STATUS_CODE='RUNNING', ATTEMPTS=ATTEMPTS+1,
                      LEASE_OWNER=?, LEASE_EPOCH=LEASE_EPOCH+1, LEASE_UNTIL=?, UPDATED_AT=?, ERROR_CODE=NULL
                    WHERE INBOX_ID=? AND TENANT_ID=? AND LEASE_EPOCH=?
                     AND ((STATUS_CODE='PENDING' AND NEXT_ATTEMPT_AT<=?)
                          OR (STATUS_CODE='RUNNING' AND LEASE_UNTIL<=?))""",
                    owner, Math.addExact(now, leaseMillis), now, m.inboxId(), tenant, m.leaseEpoch(), now, now);
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
        return jdbc.update("""
                UPDATE CHANNEL_INBOUND_INBOX SET LEASE_UNTIL=?, UPDATED_AT=?
                WHERE INBOX_ID=? AND TENANT_ID=? AND STATUS_CODE='RUNNING'
                  AND LEASE_OWNER=? AND LEASE_EPOCH=? AND LEASE_UNTIL>?""",
                until, now, c.inboxId(), c.tenantId(), c.leaseOwner(), c.leaseEpoch(), now) == 1;
    }

    @Override
    public boolean retry(InboundInboxMessage claim, long now, long next, boolean dead, String errorCode) {
        return finish(claim, now, next, dead ? InboundInboxMessage.DEAD : InboundInboxMessage.PENDING, errorCode);
    }

    private boolean finish(InboundInboxMessage c, long now, long next, String status, String error) {
        return jdbc.update("""
                UPDATE CHANNEL_INBOUND_INBOX SET STATUS_CODE=?, NEXT_ATTEMPT_AT=?, UPDATED_AT=?,
                  ERROR_CODE=?, LEASE_OWNER=NULL, LEASE_UNTIL=0
                WHERE INBOX_ID=? AND TENANT_ID=? AND STATUS_CODE='RUNNING'
                  AND LEASE_OWNER=? AND LEASE_EPOCH=? AND LEASE_UNTIL>?""",
                status, next, now, error, c.inboxId(), c.tenantId(), c.leaseOwner(), c.leaseEpoch(), now) == 1;
    }

    @Override
    public List<InboundInboxMessage> list(String tenantId, int limit) {
        return jdbc.query("""
                SELECT * FROM CHANNEL_INBOUND_INBOX WHERE TENANT_ID=?
                ORDER BY CREATED_AT DESC, INBOX_ID LIMIT ?""", this::map, tenantId, Math.clamp(limit, 1, 100));
    }

    @Override
    public boolean replay(String tenant, String id, long now) {
        return jdbc.update("""
                UPDATE CHANNEL_INBOUND_INBOX SET STATUS_CODE='PENDING', ATTEMPTS=0,
                  NEXT_ATTEMPT_AT=?, UPDATED_AT=?, ERROR_CODE=NULL
                WHERE INBOX_ID=? AND TENANT_ID=? AND STATUS_CODE='DEAD'""", now, now, id, tenant) == 1;
    }

    @Override
    public int cleanupSucceeded(long before, int limit) {
        List<String> ids = jdbc.query("""
                SELECT INBOX_ID FROM CHANNEL_INBOUND_INBOX WHERE STATUS_CODE='SUCCEEDED' AND UPDATED_AT<?
                ORDER BY UPDATED_AT, INBOX_ID LIMIT ?""", (rs, row) -> rs.getString(1), before, Math.clamp(limit, 1, 100));
        int removed = 0;
        for (String id : ids) {
            removed += jdbc.update("""
                    DELETE FROM CHANNEL_INBOUND_INBOX WHERE INBOX_ID=? AND STATUS_CODE='SUCCEEDED'
                      AND UPDATED_AT<?""", id, before);
        }
        return removed;
    }

    private InboundInboxMessage map(ResultSet r, int row) throws SQLException {
        return new InboundInboxMessage(r.getString("INBOX_ID"), r.getString("TENANT_ID"),
                r.getString("SOURCE_NAME"), r.getString("MESSAGE_ID"), r.getString("PAYLOAD_HASH"),
                r.getString("PAYLOAD_JSON"), r.getString("TRACE_ID"), r.getString("STATUS_CODE"),
                r.getInt("ATTEMPTS"), r.getString("LEASE_OWNER"), r.getLong("LEASE_EPOCH"),
                r.getLong("LEASE_UNTIL"), r.getLong("NEXT_ATTEMPT_AT"), r.getLong("CREATED_AT"),
                r.getLong("UPDATED_AT"), r.getString("ERROR_CODE"));
    }
}
