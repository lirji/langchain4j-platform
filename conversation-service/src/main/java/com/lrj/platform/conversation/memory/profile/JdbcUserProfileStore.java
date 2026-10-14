package com.lrj.platform.conversation.memory.profile;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * MySQL 用户画像权威存储。所有者行锁覆盖去重、写入、容量淘汰和清空，保证多实例行为一致。
 * 应用只校验 schema；DDL 由独立 Flyway 迁移执行，不在模型调用期间持有事务。
 */
public final class JdbcUserProfileStore implements UserProfileStore {
    private static final int MAX_ITEMS = 1000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int maxItems;

    /** 数据源由条件配置创建并管理生命周期；每个画像最多保存有界数量的条目。 */
    public JdbcUserProfileStore(DataSource dataSource, int maxItems) {
        if (maxItems < 1 || maxItems > MAX_ITEMS) {
            throw new IllegalArgumentException("profile max-items must be between 1 and 1000");
        }
        this.maxItems = maxItems;
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(10);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions.setTimeout(10);
        jdbc.queryForList("SELECT OWNER_KEY FROM USER_PROFILE_OWNER WHERE 1=0");
        jdbc.queryForList("SELECT RECORD_ID, MEMORY_ID, MEMORY_TEXT, MEMORY_TYPE, CREATED_AT, SOURCE_CHAT_ID "
                + "FROM USER_PROFILE_MEMORY WHERE 1=0");
    }

    /** 原子追加及淘汰；数据库故障直接传播，不能悄悄切回内存并丢失持久化保障。 */
    @Override
    public void add(String tenant, String user, MemoryItem item) {
        if (item == null || item.text() == null || item.text().isBlank()) return;
        String owner = ownerKey(tenant, user);
        transactions.executeWithoutResult(status -> {
            lockOwner(owner, tenant, user);
            // 锁定读确保 MySQL REPEATABLE READ 下看到前一持锁事务最新提交的条目。
            List<StoredItem> existing = read(owner, true);
            if (existing.stream().anyMatch(row -> ProfileMemoryText.overlaps(row.item().text(), item.text()))) {
                return;
            }
            jdbc.update("""
                    INSERT INTO USER_PROFILE_MEMORY
                    (OWNER_KEY, MEMORY_ID, MEMORY_TEXT, MEMORY_TYPE, CREATED_AT, SOURCE_CHAT_ID)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, owner, item.id(), item.text(), item.type(), item.createdAtEpochMs(), item.sourceChatId());
            int excess = existing.size() + 1 - maxItems;
            for (int i = 0; i < excess; i++) {
                int changed = jdbc.update("DELETE FROM USER_PROFILE_MEMORY WHERE OWNER_KEY=? AND RECORD_ID=?",
                        owner, existing.get(i).recordId());
                if (changed != 1) throw new IllegalStateException("profile eviction lost a locked record");
            }
        });
    }

    /** 返回稳定的插入顺序，保留现有 recall 从尾部截取最近记忆的语义。 */
    @Override
    public List<MemoryItem> list(String tenant, String user) {
        return read(ownerKey(tenant, user), false).stream().map(StoredItem::item).toList();
    }

    /** 清空内容并保留最小所有者锁行，避免删锁后新旧执行器在不同锁上并发操作。 */
    @Override
    public int clear(String tenant, String user) {
        String owner = ownerKey(tenant, user);
        return Objects.requireNonNull(transactions.execute(status -> {
            lockOwner(owner, tenant, user);
            return jdbc.update("DELETE FROM USER_PROFILE_MEMORY WHERE OWNER_KEY=?", owner);
        }));
    }

    private void lockOwner(String owner, String tenant, String user) {
        // 首次并发访问由唯一主键串行化；既有画像不通过删除/重建锁行进行更新。
        jdbc.update("""
                INSERT INTO USER_PROFILE_OWNER (OWNER_KEY, TENANT_ID, USER_ID) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE OWNER_KEY=OWNER_KEY
                """, owner, tenant, user);
        jdbc.queryForObject("SELECT OWNER_KEY FROM USER_PROFILE_OWNER WHERE OWNER_KEY=? FOR UPDATE", String.class, owner);
    }

    private List<StoredItem> read(String owner, boolean locked) {
        // 后缀只取自内部布尔分支；身份及全部值参数使用绑定，不接受外部 SQL。
        String sql = """
                SELECT RECORD_ID, MEMORY_ID, MEMORY_TEXT, MEMORY_TYPE, CREATED_AT, SOURCE_CHAT_ID
                FROM USER_PROFILE_MEMORY WHERE OWNER_KEY=? ORDER BY RECORD_ID LIMIT ?
                """ + (locked ? " FOR UPDATE" : "");
        List<StoredItem> items = jdbc.query(sql, (rs, row) -> new StoredItem(rs.getLong("RECORD_ID"),
                new MemoryItem(rs.getString("MEMORY_ID"), rs.getString("MEMORY_TEXT"),
                        rs.getString("MEMORY_TYPE"), rs.getLong("CREATED_AT"), rs.getString("SOURCE_CHAT_ID"))),
                owner, MAX_ITEMS + 1);
        if (items.size() > MAX_ITEMS) throw new IllegalStateException("persisted profile exceeds supported capacity");
        return items;
    }

    private static String ownerKey(String tenant, String user) {
        if (tenant == null || tenant.isBlank() || tenant.length() > 128
                || user == null || user.isBlank() || user.length() > 256) {
            throw new IllegalArgumentException("profile tenant/user identity is invalid");
        }
        try {
            // 长度定界而非 tenant + 分隔符 + user，避免身份自身含分隔符时发生桶碰撞。
            byte[] identity = (tenant.length() + ":" + tenant + user).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private record StoredItem(long recordId, MemoryItem item) {
    }
}
