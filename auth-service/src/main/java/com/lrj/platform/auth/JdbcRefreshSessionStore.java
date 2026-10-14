package com.lrj.platform.auth;

import com.lrj.platform.auth.mapper.RefreshSessionStoreMapper;
import com.lrj.platform.auth.mapper.RefreshSessionStoreRow;
import com.lrj.platform.persistence.PlusMapperFactory;
import com.lrj.platform.persistence.Rows;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JDBC 刷新会话存储（{@code AUTH_STORE=jdbc}）。只存刷新令牌哈希，支持轮转（撤旧建新）。 */
@Component
@ConditionalOnProperty(name = "app.auth.store", havingValue = "jdbc")
public class JdbcRefreshSessionStore implements RefreshSessionStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcRefreshSessionStore.class);

    private final RefreshSessionStoreMapper db;

    public JdbcRefreshSessionStore(DataSource authDataSource) {
        this.db = PlusMapperFactory.create(authDataSource, RefreshSessionStoreMapper.class);
        init();
    }

    private void init() {
        db.readInit1();
        log.info("AUTH_SESSION schema verified");
    }

    @Override
    public void create(RefreshSession session) {
        db.writeCreate1(
                session.tokenHash(),
                session.username(),
                session.createdAt().toEpochMilli(),
                session.expiresAt().toEpochMilli(),
                session.revoked());
    }

    @Override
    public Optional<RefreshSession> findByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return Optional.empty();
        }
        List<RefreshSession> rows = Rows.map(db.readFindByTokenHash1(tokenHash), this::mapSession);
        return rows.stream().findFirst();
    }

    @Override
    public void revoke(String tokenHash) {
        db.writeRevoke1(tokenHash);
    }

    @Override
    public void revokeByUsername(String username) {
        if (username != null && !username.isBlank()) {
            db.writeRevokeByUsername1(username);
        }
    }

    @Override
    public void deleteByUsername(String username) {
        if (username != null && !username.isBlank()) {
            db.writeDeleteByUsername1(username);
        }
    }

    private RefreshSession mapSession(RefreshSessionStoreRow rs, int rowNum) {
        return new RefreshSession(
                rs.tokenHash,
                rs.username,
                Instant.ofEpochMilli(Rows.longValue(rs.createdAt)),
                Instant.ofEpochMilli(Rows.longValue(rs.expiresAt)),
                Boolean.TRUE.equals(rs.revoked));
    }
}
