package com.lrj.platform.conversation.memory.profile;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** MySQL 权威画像存储；所有者锁统一保护候选、代次、容量、状态与审计。 */
public final class JdbcUserProfileStore implements GovernedProfileStore {
    private static final int MAX_ITEMS = 1000;
    private static final String COLUMNS = "RECORD_ID, MEMORY_ID, MEMORY_TEXT, MEMORY_TYPE, CREATED_AT, "
            + "SOURCE_CHAT_ID, MEMORY_STATE, REVISION, PINNED, EXPIRES_AT";
    private static final RowMapper<Entry> ENTRY = (rs, row) -> new Entry(rs.getLong("RECORD_ID"),
            rs.getString("MEMORY_ID"), rs.getString("MEMORY_TEXT"), rs.getString("MEMORY_TYPE"),
            rs.getLong("CREATED_AT"), rs.getString("SOURCE_CHAT_ID"), rs.getString("MEMORY_STATE"),
            rs.getLong("REVISION"), rs.getBoolean("PINNED"), rs.getObject("EXPIRES_AT", Long.class));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int maxItems;

    /** 应用不执行 DDL；缺少 V2 迁移时启动失败，不能降级绕过治理。 */
    public JdbcUserProfileStore(DataSource dataSource, int maxItems) {
        if (maxItems < 1 || maxItems > MAX_ITEMS) throw new IllegalArgumentException("profile max-items must be between 1 and 1000");
        this.maxItems = maxItems;
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(10);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions.setTimeout(10);
        jdbc.queryForList("SELECT GENERATION, MEMORY_ENABLED FROM USER_PROFILE_OWNER WHERE 1=0");
        jdbc.queryForList("SELECT " + COLUMNS + " FROM USER_PROFILE_MEMORY WHERE 1=0");
        jdbc.queryForList("SELECT AUDIT_ID FROM USER_PROFILE_AUDIT WHERE 1=0");
    }

    /** 保留内部已确认写入口用于迁移/兼容；模型抽取必须使用 offer。 */
    @Override
    public void add(String tenant, String user, MemoryItem item) { insert(tenant, user, item, null); }

    /** 候选与代次检查在同一所有者锁事务内，旧执行器不能覆盖用户删除。 */
    @Override
    public boolean offer(String tenant, String user, MemoryItem item, long generation) {
        return insert(tenant, user, item, generation);
    }

    private boolean insert(String tenant, String user, MemoryItem item, Long expectedGeneration) {
        if (item == null || item.text() == null || item.text().isBlank()) return false;
        validateText(item.text(), item.type());
        if (item.id() == null || item.id().length() > 128 || (item.sourceChatId() != null && item.sourceChatId().length() > 256))
            throw new IllegalArgumentException("invalid memory provenance");
        String owner = ownerKey(tenant, user);
        return Boolean.TRUE.equals(transactions.execute(status -> {
            Owner state = lockOwner(owner, tenant, user);
            if (!state.enabled() || (expectedGeneration != null && expectedGeneration != state.generation())) return false;
            List<Entry> existing = read(owner, true);
            if (existing.stream().anyMatch(row -> ProfileMemoryText.overlaps(row.text(), item.text()))) return false;
            if (existing.size() >= maxItems) {
                // 模型候选不能淘汰用户已确认的事实，固定条目也不参与容量回收。
                var victim = existing.stream().filter(row -> !row.pinned())
                        .filter(row -> expectedGeneration == null || "PENDING".equals(row.state())).findFirst();
                if (victim.isEmpty()) return false;
                delete(owner, victim.get(), "EVICT");
            }
            jdbc.update("""
                    INSERT INTO USER_PROFILE_MEMORY
                    (OWNER_KEY, MEMORY_ID, MEMORY_TEXT, MEMORY_TYPE, CREATED_AT, SOURCE_CHAT_ID, MEMORY_STATE)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, owner, item.id(), item.text(), item.type(), item.createdAtEpochMs(), item.sourceChatId(),
                    expectedGeneration == null ? "ACTIVE" : "PENDING");
            Long id = jdbc.queryForObject("SELECT MAX(RECORD_ID) FROM USER_PROFILE_MEMORY WHERE OWNER_KEY=?", Long.class, owner);
            audit(owner, Objects.requireNonNull(id), expectedGeneration == null ? "ADD" : "PROPOSE", 0);
            return true;
        }));
    }

    /** 只召回已确认且未过期的记忆；停用后立即返回空，不依赖缓存失效。 */
    @Override
    public List<MemoryItem> list(String tenant, String user) {
        Snapshot state = snapshot(tenant, user);
        if (!state.enabled()) return List.of();
        long now = System.currentTimeMillis();
        return state.items().stream().filter(row -> "ACTIVE".equals(row.state()))
                .filter(row -> row.expiresAtEpochMs() == null || row.expiresAtEpochMs() > now)
                .map(Entry::legacy).toList();
    }

    /** 物理删除正文并增加代次；保留所有者锁行及不含正文的操作审计。 */
    @Override
    public int clear(String tenant, String user) {
        String owner = ownerKey(tenant, user);
        return Objects.requireNonNull(transactions.execute(status -> {
            Owner state = lockOwner(owner, tenant, user);
            int count = jdbc.update("DELETE FROM USER_PROFILE_MEMORY WHERE OWNER_KEY=?", owner);
            bump(owner);
            audit(owner, 0, "CLEAR", state.generation() + 1);
            return count;
        }));
    }

    /** 代次在开始远程推理之前捕获；数据库不可用时不能用旧内存结果替代。 */
    @Override
    public long generation(String tenant, String user) {
        Snapshot state = snapshot(tenant, user);
        return state.enabled() ? state.generation() : -1;
    }

    /** 同一短事务取开关和条目，避免开关与内容来自不同快照。 */
    @Override
    public Snapshot snapshot(String tenant, String user) {
        String owner = ownerKey(tenant, user);
        return Objects.requireNonNull(transactions.execute(status -> {
            Owner state = lockOwner(owner, tenant, user);
            return new Snapshot(state.enabled(), state.generation(), read(owner, true));
        }));
    }

    /** 状态迁移统一入口；版本不匹配必须失败，不能返回“已保存”。 */
    @Override
    public void decide(String tenant, String user, long recordId, Decision decision) {
        if (decision == null || decision.action() == null || decision.expectedVersion() == null || decision.expectedVersion() < 0)
            throw new IllegalArgumentException("action and non-negative expectedVersion required");
        String owner = ownerKey(tenant, user);
        transactions.executeWithoutResult(status -> {
            lockOwner(owner, tenant, user);
            Entry row = read(owner, true).stream().filter(e -> e.recordId() == recordId).findFirst()
                    .orElseThrow(() -> new MemoryGovernanceException("not_found", "memory not found"));
            if (row.version() != decision.expectedVersion()) throw conflict();
            Action action = decision.action();
            if ((action == Action.APPROVE || action == Action.REJECT) && !"PENDING".equals(row.state())) throw conflict();
            if ((action == Action.PIN || action == Action.UNPIN) && !"ACTIVE".equals(row.state())) throw conflict();
            if (action == Action.FORGET || action == Action.REJECT) {
                delete(owner, row, action.name());
                bump(owner);
                return;
            }
            String text = action == Action.UPDATE ? decision.text() : row.text();
            String type = action == Action.UPDATE ? decision.type() : row.type();
            validateText(text, type);
            Long expires = action == Action.UPDATE ? decision.expiresAtEpochMs() : row.expiresAtEpochMs();
            if (expires != null && expires < 0) throw new IllegalArgumentException("expiry must be a non-negative UTC epoch value");
            boolean pinned = action == Action.PIN || (action != Action.UNPIN && row.pinned());
            int changed = jdbc.update("""
                    UPDATE USER_PROFILE_MEMORY SET MEMORY_TEXT=?, MEMORY_TYPE=?, MEMORY_STATE='ACTIVE',
                    REVISION=REVISION+1, PINNED=?, EXPIRES_AT=? WHERE OWNER_KEY=? AND RECORD_ID=? AND REVISION=?
                    """, text, type, pinned, expires, owner, recordId, row.version());
            if (changed != 1) throw conflict();
            audit(owner, recordId, action.name(), row.version() + 1);
        });
    }

    /** 用户偏好变更也使在途任务失效，避免重新启用后旧任务回填。 */
    @Override
    public void settings(String tenant, String user, long expectedGeneration, boolean enabled) {
        String owner = ownerKey(tenant, user);
        transactions.executeWithoutResult(status -> {
            Owner state = lockOwner(owner, tenant, user);
            if (state.generation() != expectedGeneration) throw conflict();
            jdbc.update("UPDATE USER_PROFILE_OWNER SET MEMORY_ENABLED=?, GENERATION=GENERATION+1 WHERE OWNER_KEY=?", enabled, owner);
            audit(owner, 0, enabled ? "ENABLE" : "DISABLE", state.generation() + 1);
        });
    }

    /** 所有分页读都限定所有者；只返回不含正文的有界审计。 */
    @Override
    public List<Audit> audit(String tenant, String user, long beforeId, int limit) {
        if (limit < 1 || limit > 100 || beforeId < 1) throw new IllegalArgumentException("invalid audit page");
        return jdbc.query("""
                SELECT AUDIT_ID, RECORD_ID, ACTION_CODE, REVISION, OCCURRED_AT FROM USER_PROFILE_AUDIT
                WHERE OWNER_KEY=? AND AUDIT_ID<? ORDER BY AUDIT_ID DESC LIMIT ?
                """, (rs, row) -> new Audit(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5)),
                ownerKey(tenant, user), beforeId, limit);
    }

    private Owner lockOwner(String owner, String tenant, String user) {
        jdbc.update("INSERT INTO USER_PROFILE_OWNER (OWNER_KEY, TENANT_ID, USER_ID) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE OWNER_KEY=OWNER_KEY", owner, tenant, user);
        return jdbc.queryForObject("SELECT GENERATION, MEMORY_ENABLED FROM USER_PROFILE_OWNER WHERE OWNER_KEY=? FOR UPDATE",
                (rs, row) -> new Owner(rs.getLong(1), rs.getBoolean(2)), owner);
    }

    private List<Entry> read(String owner, boolean locked) {
        List<Entry> rows = jdbc.query("SELECT " + COLUMNS + " FROM USER_PROFILE_MEMORY WHERE OWNER_KEY=? "
                + "ORDER BY RECORD_ID LIMIT ?" + (locked ? " FOR UPDATE" : ""), ENTRY, owner, MAX_ITEMS + 1);
        if (rows.size() > MAX_ITEMS) throw new IllegalStateException("persisted profile exceeds supported capacity");
        return rows;
    }

    private void bump(String owner) {
        if (jdbc.update("UPDATE USER_PROFILE_OWNER SET GENERATION=GENERATION+1 WHERE OWNER_KEY=?", owner) != 1) throw conflict();
    }

    private void delete(String owner, Entry row, String action) {
        if (jdbc.update("DELETE FROM USER_PROFILE_MEMORY WHERE OWNER_KEY=? AND RECORD_ID=? AND REVISION=?",
                owner, row.recordId(), row.version()) != 1) throw conflict();
        audit(owner, row.recordId(), action, row.version() + 1);
    }

    private void audit(String owner, long recordId, String action, long version) {
        jdbc.update("INSERT INTO USER_PROFILE_AUDIT (OWNER_KEY, RECORD_ID, ACTION_CODE, REVISION, OCCURRED_AT) VALUES (?, ?, ?, ?, ?)",
                owner, recordId, action, version, System.currentTimeMillis());
    }

    private static void validateText(String text, String type) {
        if (text == null || text.isBlank() || text.length() > 2000 || (type != null && type.length() > 128))
            throw new IllegalArgumentException("memory text must contain 1..2000 characters and type at most 128");
    }

    private static MemoryGovernanceException conflict() {
        return new MemoryGovernanceException("conflict", "memory state or version changed; reload before retrying");
    }

    private static String ownerKey(String tenant, String user) {
        if (tenant == null || tenant.isBlank() || tenant.length() > 128 || user == null || user.isBlank() || user.length() > 256)
            throw new IllegalArgumentException("profile tenant/user identity is invalid");
        try {
            byte[] identity = (tenant.length() + ":" + tenant + user).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 required", impossible); }
    }

    private record Owner(long generation, boolean enabled) {}
}
